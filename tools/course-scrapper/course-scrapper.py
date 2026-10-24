"""Load the McMaster undergraduate calendar's course list into the Neon `course` table.

Source: the calendar's official PDF edition, which the Registrar publishes for download. The
calendar's HTML pages sit behind an AWS WAF bot challenge; the PDF does not, and it is the
whole year's calendar in one file, so this downloads one document instead of crawling pages.

One stage, one Cloud Run Job. Exit codes are the alert, same as syllabus-pipeline:

    0   ran, courses written (or, with --verify, every check passed)
    1   the download, parse or verification failed, nothing was written
    2   misconfigured, nothing ran

Every run parses, then runs verify(): a set of checks on the parsed result that must all pass
before anything is written. `--verify` runs the same download, parse and checks, writes the
result to a CSV for review, and never touches the database.

The write is an upsert by course_code inside one transaction and never deletes. The course
table is referenced by course_enrollement, so a course that drops out of the calendar must stay.

Next academic year: set COURSE_PDF_URL to the new PDF (linked from the calendar's "PDF Version
and Archive" page) and COURSE_EXPECTED_CATALOG to the new year, then rerun with --verify.
"""

from __future__ import annotations

import argparse
import csv
import io
import logging
import os
import re
import sys
import urllib.parse as up
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, List, Optional

import psycopg2
import requests
from pypdf import PdfReader

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_MISCONFIGURED = 2

# ===================== CONFIG =====================
PDF_URL = os.getenv(
    "COURSE_PDF_URL",
    "https://academiccalendars.romcmaster.ca/mime/media/view/65/14891/ug-book-2026+pdf.pdf",
)
# A local copy instead of the download, for runs on a laptop and for tests.
PDF_PATH = os.getenv("COURSE_PDF_PATH", "").strip()
EXPECTED_CATALOG = os.getenv("COURSE_EXPECTED_CATALOG", "2026-2027")

# The 2026-2027 PDF lists about 3,300 courses. Far fewer means the parse lost a section.
MIN_COURSES = int(os.getenv("COURSE_MIN_COURSES", "2500"))
MIN_SUBJECTS = int(os.getenv("COURSE_MIN_SUBJECTS", "80"))
# Long-running courses that must be in any year's calendar. A miss means the parse is wrong,
# not that the course was cancelled; override if one ever really goes.
SPOT_CHECK_CODES = [
    c.strip()
    for c in os.getenv(
        "COURSE_SPOT_CHECKS",
        "ECON 1B03,MATH 1ZA3,CHEM 1A03,PSYCH 1X03,COMPSCI 1MD3,HISTORY 4HH3,COMPSCI 4ZP6 A/B",
    ).split(",")
    if c.strip()
]
# Share of courses allowed to look odd (title ending mid-phrase, the same code twice with two
# different titles) before the run is refused. Each one is logged either way.
MAX_SOFT_ANOMALY_RATE = 0.005

USER_AGENT = os.getenv("COURSE_USER_AGENT", "onTrack-course-loader/1.0")

TABLE_NAME = "course"
# ===================================================

log = logging.getLogger("course_scrapper")


class ConfigError(RuntimeError):
    pass


class BlockedError(RuntimeError):
    """The site answered with a bot challenge or a 403 instead of the PDF."""


class ScrapeError(RuntimeError):
    pass


@dataclass(frozen=True)
class Course:
    course_code: str
    course_name: str
    course_credits: Optional[int]


@dataclass
class ParseResult:
    courses: List[Course]
    catalog_text: str
    # Lines in the listings that start like a course heading but did not parse as one.
    unparsed_headings: List[str] = field(default_factory=list)
    # Codes listed twice with two different titles: (code, kept title, other title).
    conflicts: List[tuple] = field(default_factory=list)


# -------------------- config --------------------


def _libpq_quote(value: str) -> str:
    if value and not re.search(r"[\s'\\]", value):
        return value
    escaped = value.replace("\\", "\\\\").replace("'", "\\'")
    return f"'{escaped}'"


def resolve_dsn() -> str:
    """DATABASE_URL, or the backend's DB_URL (JDBC), DB_USERNAME and DB_PASSWORD."""
    explicit = os.getenv("DATABASE_URL", "").strip()
    if explicit:
        return explicit

    jdbc = os.getenv("DB_URL", "").strip()
    if not jdbc:
        raise ConfigError(
            "DATABASE_URL is required, or DB_URL, DB_USERNAME and DB_PASSWORD "
            "(in Cloud Run these come from Secret Manager)."
        )
    if not jdbc.startswith("jdbc:postgresql://"):
        raise ConfigError(f"DB_URL must be a postgresql JDBC url, got {jdbc!r}")

    parsed = up.urlparse(jdbc[len("jdbc:"):])
    dbname = parsed.path.lstrip("/").strip()
    if not dbname:
        raise ConfigError("DB_URL names no database")

    parts = [f"dbname={_libpq_quote(dbname)}"]
    if parsed.hostname:
        parts.append(f"host={_libpq_quote(parsed.hostname)}")
    if parsed.port:
        parts.append(f"port={parsed.port}")
    user = os.getenv("DB_USERNAME", "").strip()
    password = os.getenv("DB_PASSWORD", "")
    if user:
        parts.append(f"user={_libpq_quote(user)}")
    if password:
        parts.append(f"password={_libpq_quote(password)}")
    return " ".join(parts)


# -------------------- fetching --------------------


def _is_challenge(status: int, headers) -> bool:
    # AWS WAF answers a suspected bot with 202 and an empty body, or 403, rather than the page.
    return status in (202, 403) or "challenge" in (headers.get("x-amzn-waf-action") or "").lower()


def fetch_pdf() -> bytes:
    if PDF_PATH:
        log.info("reading the calendar PDF from %s", PDF_PATH)
        data = Path(PDF_PATH).read_bytes()
    else:
        log.info("downloading the calendar PDF from %s", PDF_URL)
        r = requests.get(PDF_URL, timeout=120, headers={"User-Agent": USER_AGENT})
        if _is_challenge(r.status_code, r.headers):
            raise BlockedError(f"{r.status_code} bot challenge from {PDF_URL}")
        r.raise_for_status()
        data = r.content
    # A moved file often comes back as an HTML error page with a 200.
    if not data.startswith(b"%PDF"):
        raise ScrapeError(f"not a PDF ({len(data)} bytes); the calendar PDF may have moved")
    log.info("got %.1f MB", len(data) / 1e6)
    return data


def pdf_pages(data: bytes) -> List[str]:
    reader = PdfReader(io.BytesIO(data))
    return [page.extract_text() or "" for page in reader.pages]


# -------------------- parsing --------------------

# SUBJECT 1A03 - TITLE, with the multi-term suffixes the backend stores verbatim (CodeFinder):
# "MUSIC 1GW3 A/B - ..." and "ASTRON 5X03 A/B S - ...". A trailing * is a footnote marker.
HEADING_RE = re.compile(
    r"^(?P<subject>[A-Z][A-Z&]{1,9}) (?P<number>\d[A-Z0-9]{2}\d)\*?"
    r"(?P<multi> A/B(?: S)?)?\*? - (?P<title>\S.*)$"
)
# Anything that starts like a heading. Lines matching this but not HEADING_RE are reported.
HEADING_LIKE_RE = re.compile(r"^[A-Z][A-Z&]{1,9} \d[A-Z0-9*]*(?: [A-Z/*]{1,4}){0,2} - ")
LISTINGS_HEADER_RE = re.compile(r"course\s+listings", re.I)


def listing_lines(pages: Iterable[str]) -> List[str]:
    """Body lines of the Course Listings section, in order, without the running headers.

    Every page starts with a running header like "| history | coUrse listinGs | 683"; only the
    pages whose header names the Course Listings section are kept.
    """
    lines: List[str] = []
    for text in pages:
        page_lines = text.splitlines()
        if not page_lines or not LISTINGS_HEADER_RE.search(page_lines[0]):
            continue
        lines.extend(page_lines[1:])
    return lines


def _is_title_continuation(line: str) -> bool:
    # Titles are printed in capitals and descriptions are not, so a wrapped title is followed
    # by more capitals and the description starts the first line with a lowercase letter.
    s = line.strip()
    return bool(s) and not re.search(r"[a-z]", s) and not HEADING_RE.match(s)


def credits_from_code(number: str) -> Optional[int]:
    """McMaster codes end in the unit weight: 1A03 is 3 units, 1D06 is 6, 2IN0 is 0."""
    return int(number[-1]) if number and number[-1].isdigit() else None


def parse_courses(pages: List[str]) -> ParseResult:
    lines = listing_lines(pages)
    catalog_text = "\n".join(pages[:3])

    found: List[Course] = []
    unparsed: List[str] = []
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        m = HEADING_RE.match(line)
        if not m:
            if HEADING_LIKE_RE.match(line):
                unparsed.append(line)
            i += 1
            continue

        title = m.group("title")
        while i + 1 < len(lines) and _is_title_continuation(lines[i + 1]):
            i += 1
            title += " " + lines[i].strip()

        number = m.group("number")
        code = f"{m.group('subject')} {number}{m.group('multi') or ''}"
        found.append(Course(code, " ".join(title.split()), credits_from_code(number)))
        i += 1

    courses, conflicts = dedupe(found)
    return ParseResult(courses, catalog_text, unparsed, conflicts)


def dedupe(courses: List[Course]) -> tuple[List[Course], List[tuple]]:
    """One row per course_code, the first listing wins. Returns (courses, conflicts)."""
    kept: dict[str, Course] = {}
    conflicts = []
    for c in courses:
        prior = kept.get(c.course_code)
        if prior is None:
            kept[c.course_code] = c
        elif prior.course_name != c.course_name:
            conflicts.append((c.course_code, prior.course_name, c.course_name))
    return list(kept.values()), conflicts


# -------------------- verification --------------------

CODE_RE = re.compile(r"^[A-Z][A-Z&]{1,9} \d[A-Z0-9]{2}\d(?: A/B(?: S)?)?$")
# A title that ends on one of these was almost certainly cut off at a line wrap.
DANGLING_END_RE = re.compile(r"(?:(?<= )(?:AND|OF|THE|IN|TO|FOR|A|AN|ON|WITH)|[,:&-])$")


def verify(result: ParseResult) -> List[str]:
    """Checks the parse must pass before anything is written. Returns the failures, or []."""
    failures: List[str] = []
    courses = result.courses
    n = len(courses)

    if EXPECTED_CATALOG not in result.catalog_text:
        failures.append(
            f"the PDF's title pages do not say {EXPECTED_CATALOG}; wrong year's calendar?"
        )

    if n < MIN_COURSES:
        failures.append(f"only {n} courses, expected at least {MIN_COURSES}")

    subjects = {c.course_code.split()[0] for c in courses}
    if len(subjects) < MIN_SUBJECTS:
        failures.append(f"only {len(subjects)} subjects, expected at least {MIN_SUBJECTS}")

    codes = {c.course_code for c in courses}
    missing = [c for c in SPOT_CHECK_CODES if c not in codes]
    if missing:
        failures.append(f"spot-check courses missing: {', '.join(missing)}")

    bad_codes = [c.course_code for c in courses if not CODE_RE.match(c.course_code)]
    if bad_codes:
        failures.append(f"{len(bad_codes)} malformed codes, e.g. {bad_codes[:5]}")

    bad_names = [
        c.course_code
        for c in courses
        if not c.course_name or len(c.course_name) > 200 or re.search(r"[a-z]", c.course_name)
    ]
    if bad_names:
        # Lowercase in a title means description text was swallowed into it.
        failures.append(f"{len(bad_names)} empty, overlong or lowercase titles, e.g. {bad_names[:5]}")

    bad_credits = [c.course_code for c in courses if c.course_credits is None]
    if bad_credits:
        failures.append(f"{len(bad_credits)} courses with no unit weight, e.g. {bad_credits[:5]}")
    elif n:
        three_unit = sum(1 for c in courses if c.course_credits == 3) / n
        if three_unit < 0.6:
            failures.append(f"only {three_unit:.0%} of courses are 3 units; unit weights look wrong")

    if result.unparsed_headings:
        failures.append(
            f"{len(result.unparsed_headings)} heading-like lines did not parse, "
            f"e.g. {result.unparsed_headings[:3]}"
        )

    dangling = [c for c in courses if DANGLING_END_RE.search(c.course_name)]
    for c in dangling:
        log.warning("title may be cut off: %s - %s", c.course_code, c.course_name)
    for code, kept, other in result.conflicts:
        log.warning("%s is listed twice: kept %r, ignored %r", code, kept, other)
    soft = len(dangling) + len(result.conflicts)
    if n and soft / n > MAX_SOFT_ANOMALY_RATE:
        failures.append(
            f"{len(dangling)} cut-off titles and {len(result.conflicts)} conflicting duplicates "
            f"exceed {MAX_SOFT_ANOMALY_RATE:.1%} of courses"
        )

    return failures


def write_csv(path: str, courses: List[Course]) -> None:
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["course_code", "course_name", "course_credits"])
        for c in sorted(courses, key=lambda c: c.course_code):
            w.writerow([c.course_code, c.course_name, c.course_credits])


# -------------------- postgres --------------------


def check_table(cur) -> None:
    """The backend's Flyway migrations own this table. This only checks it is there."""
    cur.execute("select to_regclass(%s)", (f"public.{TABLE_NAME}",))
    if cur.fetchone()[0] is None:
        raise ConfigError(
            f"table '{TABLE_NAME}' is missing. Run the backend against this database once "
            "(Flyway V1 creates it)."
        )


def save_courses(dsn: str, courses: List[Course]) -> tuple[int, int]:
    """Upsert by course_code in one transaction. Returns (inserted, updated).

    course_code has no unique constraint, so this is select-then-write under a table lock,
    which keeps two overlapping runs from both inserting the same code.
    """
    inserted = updated = 0
    with psycopg2.connect(dsn) as conn:
        with conn.cursor() as cur:
            check_table(cur)
            cur.execute(f"lock table {TABLE_NAME} in share row exclusive mode")
            cur.execute(f"select distinct course_code from {TABLE_NAME}")
            existing = {row[0] for row in cur}

            for c in courses:
                if c.course_code in existing:
                    cur.execute(
                        f"update {TABLE_NAME} set course_name = %s, course_credits = %s, "
                        f"updated_at = now() where course_code = %s",
                        (c.course_name, c.course_credits, c.course_code),
                    )
                    updated += 1
                else:
                    cur.execute(
                        f"insert into {TABLE_NAME} (course_code, course_name, course_credits, "
                        f"created_at, updated_at) values (%s, %s, %s, now(), now())",
                        (c.course_code, c.course_name, c.course_credits),
                    )
                    inserted += 1
    return inserted, updated


# -------------------- main --------------------


def load_and_verify() -> List[Course]:
    """Download, parse and verify. Raises ScrapeError if any check fails."""
    pages = pdf_pages(fetch_pdf())
    result = parse_courses(pages)
    subjects = len({c.course_code.split()[0] for c in result.courses})
    log.info("parsed %d courses across %d subjects from %d pages", len(result.courses), subjects, len(pages))

    failures = verify(result)
    for f in failures:
        log.error("verify: %s", f)
    if failures:
        raise ScrapeError(f"{len(failures)} verification check(s) failed")
    log.info("verify: all checks passed")
    return result.courses


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--verify",
        action="store_true",
        help="download, parse and check only; write a CSV and never touch the database",
    )
    parser.add_argument("--csv", default="courses.csv", help="CSV path for --verify")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        stream=sys.stdout,
    )
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    logging.getLogger("pypdf").setLevel(logging.ERROR)

    if args.verify:
        try:
            courses = load_and_verify()
        except BlockedError as exc:
            log.error("refused by the site: %s", exc)
            return EXIT_FAILED
        except Exception as exc:  # noqa: BLE001
            log.error("verification failed: %s", exc)
            return EXIT_FAILED
        write_csv(args.csv, courses)
        log.info("wrote %d courses to %s; the database was not touched", len(courses), args.csv)
        return EXIT_OK

    try:
        dsn = resolve_dsn()
    except ConfigError as exc:
        log.error("%s", exc)
        return EXIT_MISCONFIGURED

    try:
        # Fail on a bad connection or missing table before downloading and parsing.
        with psycopg2.connect(dsn) as conn, conn.cursor() as cur:
            check_table(cur)
    except ConfigError as exc:
        log.error("%s", exc)
        return EXIT_MISCONFIGURED
    except Exception as exc:  # noqa: BLE001
        log.error("cannot reach Postgres: %s", exc)
        return EXIT_MISCONFIGURED

    try:
        courses = load_and_verify()
        inserted, updated = save_courses(dsn, courses)
        log.info("done: %d inserted, %d updated in '%s'", inserted, updated, TABLE_NAME)
        return EXIT_OK
    except BlockedError as exc:
        log.error("refused by the site, nothing written: %s", exc)
        return EXIT_FAILED
    except ConfigError as exc:
        log.error("%s", exc)
        return EXIT_MISCONFIGURED
    except Exception as exc:  # noqa: BLE001
        log.error("load failed, nothing written: %s", exc)
        return EXIT_FAILED


if __name__ == "__main__":
    sys.exit(main())
