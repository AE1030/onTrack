"""Scrape the McMaster academic calendar's course list into the Neon `course` table.

One stage, one Cloud Run Job. Exit codes are the alert, same as syllabus-pipeline:

    0   ran, courses written
    1   the scrape failed or looked wrong, nothing was written
    2   misconfigured, nothing ran

The write is an upsert by course_code inside one transaction and never deletes. The course
table is referenced by course_enrollement, so a course that drops out of the calendar must stay.
"""

from __future__ import annotations

import asyncio
import logging
import os
import random
import re
import ssl
import sys
import time
import urllib.parse as up
from dataclasses import dataclass
from typing import List, Optional

import aiohttp
import psycopg2
import requests
from bs4 import BeautifulSoup

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_MISCONFIGURED = 2

# ===================== CONFIG =====================
BASE = "https://academiccalendars.romcmaster.ca"
CATOID = os.getenv("COURSE_CATOID", "65")
NAVOID = os.getenv("COURSE_NAVOID", "14802")
EXPECTED_CATALOG = os.getenv("COURSE_EXPECTED_CATALOG", "2026-2027")

# The filters are the ones the calendar's own course search sends: courses only, active only.
LIST_URL = (
    BASE
    + "/content.php?catoid={catoid}&navoid={navoid}"
    + "&filter%5Bitem_type%5D=3&filter%5Bonly_active%5D=1&filter%5B3%5D=1"
    + "&filter%5Bcpage%5D={page}"
)
PREVIEW_URL = BASE + "/preview_course_nopop.php?catoid={catoid}&coid={coid}"

START_PAGE = 1
# Pages are walked until one adds nothing new. This only stops a runaway loop.
MAX_PAGES = int(os.getenv("COURSE_MAX_PAGES", "200"))

SLEEP_MIN, SLEEP_MAX = 0.1, 0.3
CONCURRENCY = int(os.getenv("COURSE_CONCURRENCY", "8"))
SSL_VERIFY = os.getenv("COURSE_SSL_VERIFY", "true").strip().lower() != "false"

# Abort without writing when more than this share of course pages fail, or when fewer courses
# than this come back at all. Either means the site changed or is refusing us, and a half
# catalog is worse than the one already in the table.
MAX_FAILURE_RATE = 0.02
MIN_COURSES = int(os.getenv("COURSE_MIN_COURSES", "1000"))

USER_AGENT = os.getenv(
    "COURSE_USER_AGENT",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0 Safari/537.36",
)

TABLE_NAME = "course"
# ===================================================

log = logging.getLogger("course_scrapper")


class ConfigError(RuntimeError):
    pass


class BlockedError(RuntimeError):
    """The site answered with a bot challenge or a 403 instead of the page."""


class ScrapeError(RuntimeError):
    pass


@dataclass(frozen=True)
class Course:
    course_code: str
    course_name: str
    course_credits: Optional[int]


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


# -------------------- parsing --------------------

COID_HREF_RE = re.compile(r"preview_course_nopop\.php\?[^\"'>]*\bcoid=(\d+)", re.I)


def find_coids_in_list_html(html: str) -> List[str]:
    soup = BeautifulSoup(html, "html.parser")
    coids = []
    for a in soup.find_all("a", href=True):
        m = COID_HREF_RE.search(a["href"])
        if m:
            coids.append(m.group(1))
    return coids


def first_nonempty(*vals: Optional[str]) -> str:
    for v in vals:
        if v and v.strip():
            return v.strip()
    return ""


def split_code_name(raw_title: str):
    t = " ".join(raw_title.split())
    if " - " in t:
        code, name = t.split(" - ", 1)
    else:
        parts = t.split()
        code = parts[0] if parts else ""
        name = " ".join(parts[1:]) if len(parts) > 1 else ""
    return code.strip(), name.strip()


def extract_credits(code: str, text_block: str) -> Optional[int]:
    m = re.search(r"\b(\d+)\s*(?:unit|units|credit|credits)\b", text_block, re.I)
    if m:
        try:
            return int(m.group(1))
        except ValueError:
            pass
    nums = re.findall(r"\d+", code)
    if nums:
        try:
            return int(nums[-1])
        except ValueError:
            return None
    return None


def parse_preview_html(html: str) -> Course:
    soup = BeautifulSoup(html, "html.parser")
    candidate_texts = []
    for sel in [
        "#course_preview_title",
        ".courseblocktitle",
        ".ui-catalog-title",
        "h1.course-title",
        "h2.course-title",
        "h1",
        "h2",
        "h3",
    ]:
        for el in soup.select(sel):
            txt = el.get_text(" ", strip=True)
            if txt:
                candidate_texts.append(txt)

    title_text = first_nonempty(*candidate_texts)
    if not title_text:
        preview_container = soup.find(id="course_preview_container") or soup
        strong = preview_container.find("strong")
        title_text = strong.get_text(" ", strip=True) if strong else ""

    code, name = split_code_name(title_text)
    container = (
        soup.find(id="course_preview_container")
        or soup.find("div", class_="coursepadding")
        or soup
    )
    full_text = container.get_text("\n", strip=True)
    credits = extract_credits(code, full_text)

    return Course(course_code=code, course_name=name, course_credits=credits)


# -------------------- fetching --------------------


def _is_challenge(status: int, headers) -> bool:
    # AWS WAF answers a suspected bot with 202 and an empty body, or 403, rather than the page.
    return status in (202, 403) or "challenge" in (headers.get("x-amzn-waf-action") or "").lower()


def get_text_requests(session: requests.Session, url: str) -> str:
    r = session.get(url, timeout=20, verify=SSL_VERIFY)
    if _is_challenge(r.status_code, r.headers):
        raise BlockedError(f"{r.status_code} bot challenge from {BASE}")
    r.raise_for_status()
    return r.text


def collect_coids() -> List[str]:
    """Every course id across the list pages. Any page that fails aborts the scrape."""
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT})

    seen, all_coids = set(), []
    for page in range(START_PAGE, MAX_PAGES + 1):
        url = LIST_URL.format(catoid=CATOID, navoid=NAVOID, page=page)
        try:
            html = get_text_requests(session, url)
        except BlockedError:
            raise
        except Exception as exc:
            # A silently skipped page is a silently missing slice of the alphabet.
            raise ScrapeError(f"list page {page} failed: {exc}") from exc

        if page == START_PAGE:
            title = BeautifulSoup(html, "html.parser").title
            title_text = title.get_text(" ", strip=True) if title else ""
            log.info("catalog page title: %r", title_text)
            if EXPECTED_CATALOG not in html:
                log.warning(
                    "%s does not appear on the first list page; catoid=%s may be the wrong year",
                    EXPECTED_CATALOG,
                    CATOID,
                )

        coids = find_coids_in_list_html(html)
        new = [c for c in coids if c not in seen]
        seen.update(new)
        all_coids.extend(new)
        log.info("list page %d: %d coids, %d new (total %d)", page, len(coids), len(new), len(all_coids))

        # Past the last page the site returns nothing, or repeats the last page. Either way
        # nothing new turns up, which is the signal to stop.
        if not new:
            log.info("page %d added nothing new, so page %d was the last", page, page - 1)
            break
        time.sleep(random.uniform(SLEEP_MIN, SLEEP_MAX))
    else:
        raise ScrapeError(f"still finding new courses at page {MAX_PAGES}, raise COURSE_MAX_PAGES")

    return all_coids


def _ssl_context() -> ssl.SSLContext:
    ctx = ssl.create_default_context()
    if not SSL_VERIFY:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    return ctx


async def _fetch_one_coid(
    session: aiohttp.ClientSession, coid: str, sem: asyncio.Semaphore
) -> Optional[Course]:
    url = PREVIEW_URL.format(catoid=CATOID, coid=coid)
    backoffs = [0.25, 0.5, 1.0, 2.0]
    async with sem:
        for i, wait in enumerate(backoffs, 1):
            try:
                async with session.get(url) as resp:
                    if _is_challenge(resp.status, resp.headers):
                        raise BlockedError(f"{resp.status} bot challenge for coid={coid}")
                    resp.raise_for_status()
                    html = await resp.text()
                    c = parse_preview_html(html)
                    if not c.course_code or not c.course_name:
                        soup = BeautifulSoup(html, "html.parser")
                        t = soup.title.get_text(" ", strip=True) if soup.title else ""
                        cc, nn = split_code_name(t)
                        c = Course(
                            course_code=c.course_code or cc,
                            course_name=c.course_name or nn,
                            course_credits=c.course_credits,
                        )
                    if not c.course_code or not c.course_name:
                        raise ScrapeError("page had no course code or name")
                    return c
            except BlockedError:
                raise
            except Exception as exc:
                if i == len(backoffs):
                    log.warning("coid=%s failed after retries: %s", coid, exc)
                    return None
                await asyncio.sleep(wait)
    return None


async def scrape_details(coids: List[str]) -> tuple[List[Course], int]:
    conn = aiohttp.TCPConnector(limit=CONCURRENCY, ssl=_ssl_context(), ttl_dns_cache=300)
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml",
        "Referer": LIST_URL.format(catoid=CATOID, navoid=NAVOID, page=START_PAGE),
    }
    sem = asyncio.Semaphore(CONCURRENCY)
    courses: List[Course] = []
    failed = 0

    async with aiohttp.ClientSession(
        connector=conn, headers=headers, timeout=aiohttp.ClientTimeout(total=35)
    ) as session:
        tasks = [asyncio.create_task(_fetch_one_coid(session, c, sem)) for c in coids]
        try:
            for done, coro in enumerate(asyncio.as_completed(tasks), start=1):
                result = await coro
                if result:
                    courses.append(result)
                else:
                    failed += 1
                if done % 100 == 0 or done == len(tasks):
                    log.info("%d/%d fetched (ok=%d)", done, len(tasks), len(courses))
        except BlockedError:
            for t in tasks:
                t.cancel()
            raise
    return courses, failed


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


def dedupe(courses: List[Course]) -> List[Course]:
    """One row per course_code. The first page seen wins."""
    seen, out = set(), []
    for c in courses:
        code = c.course_code.strip()
        if code and code not in seen:
            seen.add(code)
            out.append(Course(code, c.course_name.strip(), c.course_credits))
    return out


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        stream=sys.stdout,
    )
    logging.getLogger("urllib3").setLevel(logging.WARNING)

    try:
        dsn = resolve_dsn()
    except ConfigError as exc:
        log.error("%s", exc)
        return EXIT_MISCONFIGURED

    try:
        # Fail on a bad connection or missing table before spending minutes scraping.
        with psycopg2.connect(dsn) as conn, conn.cursor() as cur:
            check_table(cur)
    except ConfigError as exc:
        log.error("%s", exc)
        return EXIT_MISCONFIGURED
    except Exception as exc:  # noqa: BLE001
        log.error("cannot reach Postgres: %s", exc)
        return EXIT_MISCONFIGURED

    try:
        log.info("collecting coids from list pages (catoid=%s)", CATOID)
        coids = collect_coids()
        if not coids:
            raise ScrapeError("no coids found, check catoid, navoid and pagination")
        log.info("%d unique coids; fetching details (concurrency=%d)", len(coids), CONCURRENCY)

        courses, failed = asyncio.run(scrape_details(coids))
        deduped = dedupe(courses)
        log.info("parsed %d, failed %d, unique course codes %d", len(courses), failed, len(deduped))

        if failed / len(coids) > MAX_FAILURE_RATE:
            raise ScrapeError(f"{failed}/{len(coids)} course pages failed, not writing")
        if len(deduped) < MIN_COURSES:
            raise ScrapeError(
                f"only {len(deduped)} courses, expected at least {MIN_COURSES}. Not writing"
            )

        inserted, updated = save_courses(dsn, deduped)
        log.info("done: %d inserted, %d updated in '%s'", inserted, updated, TABLE_NAME)
        return EXIT_OK
    except BlockedError as exc:
        log.error("refused by the site, nothing written: %s", exc)
        return EXIT_FAILED
    except ConfigError as exc:
        log.error("%s", exc)
        return EXIT_MISCONFIGURED
    except Exception as exc:  # noqa: BLE001
        log.error("scrape failed, nothing written: %s", exc)
        return EXIT_FAILED


if __name__ == "__main__":
    sys.exit(main())
