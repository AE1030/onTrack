"""Postgres access for the pipeline. All of the pipeline's state lives here.

The old pipeline kept its state in three text files next to the scripts: an append-only
completed list, a failed list, and a not-completed list. All three drifted from the database
inside a single term, and none of it was wrong on purpose. They drifted because they were a
second record of something the database already knew, updated on a different schedule.

So there are no state files. "What still needs doing" is a query, and a run that dies halfway
leaves the next run with a correct picture by construction.

Three tables, all created by the backend's Flyway migration V16 rather than by this module:

    syllabus_source        one row per scraped section, keyed by doc_code. Holds the flattened
                           text the extractor reads and a sha256 of it.
    syllabus               the catalog the backend reads, keyed by (course_code, term, doc_code).
                           Carries source_text_sha256, which is how extract knows what is stale.
    syllabus_pipeline_run  one row per run: counts, timings, and any verify violations.

This replaced a MongoDB store when the collections were folded into Postgres. Two things about
that are worth knowing before changing anything here:

  * The JSON written into `assessments` is the extractor's output unchanged, snake_case keys and
    all. The backend reads it with a Jackson mapper configured for SNAKE_CASE
    (SyllabusAssessmentsJsonConverter), so the key names are a cross-language contract. Renaming
    a key here silently deserialises as null over there.
  * Flyway owns the schema. `ensure_indexes` therefore checks that the tables exist rather than
    creating anything, so a pipeline pointed at a database that was never migrated fails at
    startup with a clear message instead of on its first write.
"""

from __future__ import annotations

import hashlib
import json
import zlib
from datetime import datetime, timezone
from typing import Any, Iterator, Optional

import psycopg2
import psycopg2.extras

SOURCES = "syllabus_source"
CATALOG = "syllabus"
RUNS = "syllabus_pipeline_run"


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class Store:
    def __init__(self, dsn: str):
        # A scrape of 1250 documents can outlast a short connect timeout on a cold managed
        # instance, and a Cloud Run Job that dies at minute 8 of 10 is worse than one that waits.
        self._conn = psycopg2.connect(dsn, connect_timeout=20)
        # Every method here is self-contained, so committing as we go is what makes a run that
        # dies halfway leave a usable picture behind rather than rolling back the whole scrape.
        self._conn.autocommit = True

    def close(self) -> None:
        self._conn.close()

    def __enter__(self) -> "Store":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def _cursor(self):
        return self._conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    def ping(self) -> None:
        with self._cursor() as cur:
            cur.execute("select 1")
            cur.fetchone()

    def ensure_indexes(self) -> None:
        """Assert the schema is present. Flyway creates it; this only checks.

        Kept under the old name because the CLI calls it at exactly the right moment — after
        connecting, before any stage runs — which is where this check belongs. Creating indexes
        from here would put two owners on one schema, and the loser would be whichever ran second.
        """
        missing = []
        with self._cursor() as cur:
            for table in (SOURCES, CATALOG, RUNS):
                cur.execute("select to_regclass(%s) as oid", (f"public.{table}",))
                if cur.fetchone()["oid"] is None:
                    missing.append(table)
        if missing:
            raise RuntimeError(
                "missing table(s): "
                + ", ".join(missing)
                + ". The backend's Flyway migrations own this schema -- run the backend against "
                "this database once (V16 creates them), or apply V16__mongo_to_postgres.sql by hand."
            )

    # ---------------------------------------------------------------- sources

    def known_source_codes(self) -> set[str]:
        with self._cursor() as cur:
            cur.execute(f"select doc_code from {SOURCES}")
            return {row["doc_code"] for row in cur}

    def upsert_source(
        self,
        doc_code: str,
        listing: dict[str, Any],
        text: str,
        raw: Optional[dict[str, Any]],
        run_id: str,
    ) -> str:
        """Store one scraped section. Returns the text's sha256.

        The raw API response is stored zlib-compressed in a bytea column rather than as jsonb. It
        is ~120KB of mostly-HTML per section, 144MB across the corpus, and nothing queries into
        it -- it exists so a prompt change can be re-run without touching the site again.
        Compressed it is roughly a tenth of that, and as an opaque blob it cannot trip over
        Postgres's rules for jsonb keys the way arbitrary vendor JSON can.
        """
        digest = sha256(text)
        raw_gz = (
            psycopg2.Binary(zlib.compress(json.dumps(raw, default=str).encode("utf-8"), 6))
            if raw is not None
            else None
        )
        with self._cursor() as cur:
            cur.execute(
                f"""
                insert into {SOURCES} (doc_code, course_code, term, title_raw, subtitle,
                                       entity_id, entity_type, family_name, text, text_sha256,
                                       text_length, fetched_at, scrape_run_id, raw_gz)
                values (%(doc_code)s, %(course_code)s, %(term)s, %(title_raw)s, %(subtitle)s,
                        %(entity_id)s, %(entity_type)s, %(family_name)s, %(text)s, %(text_sha256)s,
                        %(text_length)s, %(fetched_at)s, %(scrape_run_id)s, %(raw_gz)s)
                on conflict (doc_code) do update set
                    course_code   = excluded.course_code,
                    term          = excluded.term,
                    title_raw     = excluded.title_raw,
                    subtitle      = excluded.subtitle,
                    entity_id     = excluded.entity_id,
                    entity_type   = excluded.entity_type,
                    family_name   = excluded.family_name,
                    text          = excluded.text,
                    text_sha256   = excluded.text_sha256,
                    text_length   = excluded.text_length,
                    fetched_at    = excluded.fetched_at,
                    scrape_run_id = excluded.scrape_run_id,
                    -- A run with SYLLABUS_STORE_RAW off must not wipe a blob an earlier run
                    -- stored, so a null incoming value leaves the existing one alone.
                    raw_gz        = coalesce(excluded.raw_gz, {SOURCES}.raw_gz)
                """,
                {
                    "doc_code": doc_code,
                    "course_code": listing.get("course_code"),
                    "term": listing.get("term_name"),
                    "title_raw": listing.get("title_raw"),
                    "subtitle": listing.get("subtitle"),
                    "entity_id": listing.get("entity_id"),
                    "entity_type": listing.get("entity_type"),
                    "family_name": listing.get("family_name"),
                    "text": text,
                    "text_sha256": digest,
                    "text_length": len(text),
                    "fetched_at": utc_now(),
                    "scrape_run_id": run_id,
                    "raw_gz": raw_gz,
                },
            )
        return digest

    def raw_for(self, doc_code: str) -> Optional[dict[str, Any]]:
        """Decompress a stored raw response. Only used for debugging a bad extraction."""
        with self._cursor() as cur:
            cur.execute(f"select raw_gz from {SOURCES} where doc_code = %s", (doc_code,))
            row = cur.fetchone()
        if not row or row["raw_gz"] is None:
            return None
        return json.loads(zlib.decompress(bytes(row["raw_gz"])).decode("utf-8"))

    def iter_sources(self, terms: Optional[list[str]] = None) -> Iterator[dict[str, Any]]:
        """Every source, ordered by doc_code, with the raw blob left behind.

        raw_gz is excluded rather than selected and discarded: an extract pass holds this result
        open across a Gemini call per document, and dragging 144MB of compressed HTML through the
        connection to ignore it would be the slowest part of the stage.
        """
        columns = (
            "doc_code, course_code, term, title_raw, subtitle, entity_id, entity_type, "
            "family_name, text, text_sha256, text_length, fetched_at, scrape_run_id"
        )
        with self._cursor() as cur:
            if terms:
                cur.execute(
                    f"select {columns} from {SOURCES} where term = any(%s) order by doc_code",
                    (list(terms),),
                )
            else:
                cur.execute(f"select {columns} from {SOURCES} order by doc_code")
            # Materialised deliberately. The caller runs a Gemini call per row, and holding a
            # server-side cursor open across those would keep a transaction alive for the length
            # of the whole stage.
            for row in cur.fetchall():
                yield dict(row)

    # ---------------------------------------------------------------- catalog

    def extracted_source_hashes(self) -> dict[str, str]:
        """doc_code -> the source sha256 its catalog entry was extracted from.

        Anything absent from this map, or mapped to a different hash than the source now
        has, needs extracting. That is the entire staleness rule, and it means a syllabus
        edited mid-term gets picked up on the next run for free.
        """
        with self._cursor() as cur:
            cur.execute(f"select doc_code, source_text_sha256 from {CATALOG}")
            return {row["doc_code"]: (row["source_text_sha256"] or "") for row in cur}

    def upsert_extraction(
        self,
        doc_code: str,
        course_code: str,
        term: str,
        assessments: dict[str, Any],
        source_sha: str,
        model: str,
        temperature: float,
        run_id: str,
    ) -> None:
        now = utc_now()
        with self._cursor() as cur:
            cur.execute(
                f"""
                insert into {CATALOG} (course_code, term, doc_code, assessments, extraction,
                                       source_text_sha256, extracted_at, extract_run_id,
                                       created_at, updated_at)
                values (%(course_code)s, %(term)s, %(doc_code)s, %(assessments)s, %(extraction)s,
                        %(source_sha)s, %(extracted_at)s, %(run_id)s, %(now)s, %(now)s)
                on conflict (course_code, term, doc_code) do update set
                    assessments        = excluded.assessments,
                    extraction         = excluded.extraction,
                    source_text_sha256 = excluded.source_text_sha256,
                    extracted_at       = excluded.extracted_at,
                    extract_run_id     = excluded.extract_run_id,
                    updated_at         = excluded.updated_at
                """,
                {
                    "course_code": course_code,
                    "term": term,
                    "doc_code": doc_code,
                    # The extractor's JSON goes in untouched. Its snake_case keys are what the
                    # backend's SNAKE_CASE mapper expects; see this module's docstring.
                    "assessments": psycopg2.extras.Json(assessments),
                    "extraction": psycopg2.extras.Json(
                        {"model": model, "temperature": temperature}
                    ),
                    "source_sha": source_sha,
                    "extracted_at": now,
                    "run_id": run_id,
                    "now": now,
                },
            )

    def iter_catalog(self, terms: Optional[list[str]] = None) -> Iterator[dict[str, Any]]:
        with self._cursor() as cur:
            if terms:
                cur.execute(
                    f"select * from {CATALOG} where term = any(%s) order by course_code, term, doc_code",
                    (list(terms),),
                )
            else:
                cur.execute(f"select * from {CATALOG} order by course_code, term, doc_code")
            for row in cur.fetchall():
                yield dict(row)

    # ---------------------------------------------------------------- runs

    def start_run(self, run_id: str, stages: list[str], trigger: str) -> None:
        with self._cursor() as cur:
            cur.execute(
                f"""
                insert into {RUNS} (id, started_at, finished_at, stages, trigger, status, results)
                values (%s, %s, null, %s, %s, 'running', %s)
                """,
                (
                    run_id,
                    utc_now(),
                    psycopg2.extras.Json(stages),
                    trigger,
                    psycopg2.extras.Json({}),
                ),
            )

    def finish_run(self, run_id: str, status: str, results: dict[str, Any]) -> None:
        with self._cursor() as cur:
            cur.execute(
                f"update {RUNS} set finished_at = %s, status = %s, results = %s where id = %s",
                (utc_now(), status, psycopg2.extras.Json(results, dumps=_dumps), run_id),
            )

    def last_runs(self, limit: int = 10) -> list[dict[str, Any]]:
        with self._cursor() as cur:
            cur.execute(
                f"select * from {RUNS} order by started_at desc nulls last limit %s", (limit,)
            )
            return [dict(row) for row in cur.fetchall()]


def _dumps(value: Any) -> str:
    """json.dumps that tolerates what a run report actually contains.

    A verify report carries Violation objects and datetimes. Mongo's BSON encoder accepted
    datetimes directly, so this never came up before; psycopg2's Json needs to be told.
    """
    return json.dumps(value, default=str)
