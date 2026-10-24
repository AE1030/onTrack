"""Every knob the pipeline has, resolved from the environment in one place.

Nothing here reads a file on disk and nothing here has a secret baked into it. That is the
whole point: the same image runs on a laptop and as a Cloud Run Job, and the only difference
is what the environment holds.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from typing import Optional
from urllib.parse import urlparse


class ConfigError(RuntimeError):
    """A required setting is missing or unusable. Raised at startup, never mid-run."""


def _require(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise ConfigError(
            f"{name} is required. In Cloud Run it comes from Secret Manager; "
            f"locally, export it or put it in a .env you do not commit."
        )
    return value


def _flag(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _number(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        return float(raw)
    except ValueError as exc:
        raise ConfigError(f"{name} must be a number, got {raw!r}") from exc


def _integer(name: str, default: Optional[int]) -> Optional[int]:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise ConfigError(f"{name} must be a whole number, got {raw!r}") from exc


@dataclass(frozen=True)
class ScrapeConfig:
    base_url: str
    term_statuses: tuple[str, ...]
    user_agent: str
    page_size: int
    request_delay_sec: float
    timeout_sec: float
    max_retries: int
    max_new_docs: Optional[int]
    store_raw: bool

    @property
    def library_search_url(self) -> str:
        return f"{self.base_url}/api2/doc-library-search"

    @property
    def full_page_url(self) -> str:
        return f"{self.base_url}/api2/doc-full-page-get"


@dataclass(frozen=True)
class ExtractConfig:
    api_key: str
    model: str
    temperature: float
    thinking_budget: int
    max_docs_per_run: Optional[int]
    request_delay_sec: float
    max_retries: int
    # Attempts for an answer with no grading scheme, which is usually the syllabus, not a fluke:
    # in the 2026-10-24 run two retries rescued 1 document in 36. Recorded and skipped after.
    no_scheme_attempts: int = 2


@dataclass(frozen=True)
class PostgresConfig:
    """Connection details for the database the backend also uses.

    One DSN rather than a URI plus a database name, because libpq carries the database in the
    connection string and psycopg2 wants it that way round.
    """

    dsn: str


@dataclass(frozen=True)
class Config:
    postgres: PostgresConfig
    scrape: ScrapeConfig
    fail_on_violations: bool
    _extract: Optional[ExtractConfig] = field(default=None)

    @property
    def extract(self) -> ExtractConfig:
        """Resolved lazily so `scrape` and `verify` run without a Gemini key in the env."""
        if self._extract is None:
            raise ConfigError(
                "GEMINI_API_KEY is required for the extract stage. "
                "Run with --stages scrape,verify if you only meant to refresh sources."
            )
        return self._extract


def load(require_gemini: bool = False) -> Config:
    dsn = _resolve_dsn()

    extract_key = os.getenv("GEMINI_API_KEY", "").strip()
    if require_gemini and not extract_key:
        _require("GEMINI_API_KEY")  # raises with the helpful message

    extract = (
        ExtractConfig(
            api_key=extract_key,
            model=os.getenv("GEMINI_MODEL", "gemini-2.5-pro"),
            temperature=_number("GEMINI_TEMPERATURE", 0.3),
            thinking_budget=int(_number("GEMINI_THINKING_BUDGET", 8311)),
            max_docs_per_run=_integer("MAX_EXTRACTIONS_PER_RUN", None),
            request_delay_sec=_number("GEMINI_REQUEST_DELAY_SEC", 0.2),
            max_retries=int(_number("GEMINI_MAX_RETRIES", 3)),
            no_scheme_attempts=int(_number("GEMINI_NO_SCHEME_ATTEMPTS", 2)),
        )
        if extract_key
        else None
    )

    return Config(
        postgres=PostgresConfig(dsn=dsn),
        scrape=ScrapeConfig(
            base_url=os.getenv(
                "SYLLABUS_BASE_URL", "https://mcmaster.simplesyllabusca.com"
            ).rstrip("/"),
            term_statuses=tuple(
                s.strip()
                for s in os.getenv("SYLLABUS_TERM_STATUSES", "current,future").split(",")
                if s.strip()
            ),
            # A desktop Chrome string. The document endpoint (api2/doc-full-page-get) sits behind
            # an AWS WAF rule that returns a CloudFront 403 to any User-Agent that does not look
            # like a browser, and an honest identifying string is refused. The listing endpoint
            # is not covered by the rule. Override with SYLLABUS_USER_AGENT if this goes stale.
            user_agent=os.getenv(
                "SYLLABUS_USER_AGENT",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            ),
            page_size=int(_number("SYLLABUS_PAGE_SIZE", 50)),
            # Four times the old 0.15s. A cold run is ~1250 fetches either way; the delay is
            # what decides whether that reads as a burst or as background noise.
            request_delay_sec=_number("SYLLABUS_REQUEST_DELAY_SEC", 0.6),
            timeout_sec=_number("SYLLABUS_TIMEOUT_SEC", 30),
            max_retries=int(_number("SYLLABUS_MAX_RETRIES", 5)),
            max_new_docs=_integer("SYLLABUS_MAX_NEW_DOCS", None),
            store_raw=_flag("SYLLABUS_STORE_RAW", True),
        ),
        fail_on_violations=_flag("FAIL_ON_VIOLATIONS", True),
        _extract=extract,
    )


def _resolve_dsn() -> str:
    """The libpq connection string, from either of two shapes of environment.

    DATABASE_URL wins when set, because an explicit DSN is unambiguous and is what a Cloud Run Job will be given.

    Failing that, the DSN is assembled from the three variables the backend already defines --
    DB_URL, DB_USERNAME, DB_PASSWORD -- so the pipeline runs against the same database with the
    same env file and no duplicated settings. That means translating a JDBC URL, which psycopg2
    cannot parse: `jdbc:postgresql://host:5432/db` is not a libpq URI until the `jdbc:` prefix
    comes off.
    """
    explicit = os.getenv("DATABASE_URL", "").strip()
    if explicit:
        return explicit

    jdbc = os.getenv("DB_URL", "").strip()
    if not jdbc:
        raise ConfigError(
            "DATABASE_URL is required (or DB_URL, DB_USERNAME and DB_PASSWORD, which the backend "
            "already sets). In Cloud Run it comes from Secret Manager; locally, export it or put "
            "it in a .env you do not commit."
        )

    return _dsn_from_jdbc(jdbc, os.getenv("DB_USERNAME", "").strip(), os.getenv("DB_PASSWORD", ""))


def _dsn_from_jdbc(jdbc_url: str, user: str, password: str) -> str:
    """Translate the backend's JDBC URL into a libpq DSN.

    The result is keyword/value form (`host=... dbname=...`), not a URI. libpq accepts either one
    but not a URI with keywords appended, which is a mistake that fails at connect time with the
    unhelpful "no password supplied" rather than a parse error. Keyword form also means a password
    containing `@`, `/` or `:` needs no percent-encoding -- only libpq quoting, which `_libpq_quote`
    handles.

    The host is allowed to be absent. `jdbc:postgresql:///tracker_db?cloudSqlInstance=...` is the
    shape the old production URL had, where the connection went over a unix socket and the host was
    supplied by a JDBC socket factory that has no libpq equivalent.
    """
    if not jdbc_url.startswith("jdbc:postgresql://"):
        raise ConfigError(
            f"DB_URL must be a postgresql JDBC url, got {jdbc_url!r}. "
            "Set DATABASE_URL instead if the pipeline talks to a different database."
        )

    parsed = urlparse(jdbc_url[len("jdbc:") :])
    dbname = parsed.path.lstrip("/").strip()
    if not dbname:
        raise ConfigError(
            "DB_URL names no database, for example jdbc:postgresql://host:5432/tracker_db"
        )

    # Query parameters are JDBC's, not libpq's: cloudSqlInstance and socketFactory mean nothing
    # here, and passing them through would make libpq reject the whole DSN. urlparse has already
    # separated them from the path.
    parts = [f"dbname={_libpq_quote(dbname)}"]
    if parsed.hostname:
        parts.append(f"host={_libpq_quote(parsed.hostname)}")
    if parsed.port:
        parts.append(f"port={parsed.port}")
    if user:
        parts.append(f"user={_libpq_quote(user)}")
    if password:
        parts.append(f"password={_libpq_quote(password)}")
    return " ".join(parts)


def _libpq_quote(value: str) -> str:
    """Single-quote a libpq connection value if it needs it.

    libpq splits keyword/value strings on whitespace, so a value containing a space, a quote or a
    backslash has to be quoted and escaped or the DSN silently means something else.
    """
    if value and not re.search(r"[\s'\\]", value):
        return value
    escaped = value.replace("\\", "\\\\").replace("'", "\\'")
    return f"'{escaped}'"
