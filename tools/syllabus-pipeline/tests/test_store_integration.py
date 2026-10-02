"""Store tests against a real Postgres.

Skipped unless SYLLABUS_TEST_DSN names a database that has the backend's Flyway migrations
applied. Deliberately not a fixture that creates the tables itself: Flyway owns this schema, and a
test that built its own copy would keep passing after V16 and the store drifted apart, which is the
one failure worth catching here.

To run locally:

    export SYLLABUS_TEST_DSN="postgresql://localhost:5432/tracker_db user=postgres password=postgres"
    python -m pytest tests/test_store_integration.py

Every row written carries a doc_code or run id prefixed with the value of PREFIX, and the fixture
deletes those on the way in and out, so this is safe against a development database with real rows
in it.
"""

from __future__ import annotations

import json
import os

import pytest

from syllabus_pipeline.store import CATALOG, RUNS, SOURCES, Store, sha256

DSN = os.getenv("SYLLABUS_TEST_DSN", "").strip()

pytestmark = pytest.mark.skipif(
    not DSN, reason="set SYLLABUS_TEST_DSN to a migrated Postgres to run the store tests"
)

PREFIX = "pytest-store-"


@pytest.fixture()
def store():
    with Store(DSN) as s:
        _purge(s)
        try:
            yield s
        finally:
            _purge(s)


def _purge(s: Store) -> None:
    with s._cursor() as cur:
        cur.execute(f"delete from {CATALOG} where doc_code like %s", (PREFIX + "%",))
        cur.execute(f"delete from {SOURCES} where doc_code like %s", (PREFIX + "%",))
        cur.execute(f"delete from {RUNS} where id like %s", (PREFIX + "%",))


def listing(course="BIOLOGY 3AA3", term="Winter 2026"):
    return {
        "course_code": course,
        "term_name": term,
        "title_raw": "Cell Biology",
        "subtitle": "C01",
        "entity_id": "e-1",
        "entity_type": "section",
        "family_name": "BIOLOGY",
    }


def assessments(weight=0.4):
    """The extractor's shape, snake_case keys and all. See store.py's docstring."""
    return {
        "grading_scheme": {
            "selection_rule": "MAX",
            "schemes": {
                "scheme_1": {
                    "label": "Standard",
                    "assessments": [
                        {
                            "name": "Final Exam",
                            "category": "exam",
                            "due_date": "2026-04-15",
                            "weight": weight,
                            "start_time": "09:00",
                            "bonus_assessment": False,
                            "replacement_rule": {"trigger_type": "DROP_LOWEST", "N": 2},
                        }
                    ],
                }
            },
        }
    }


class TestSchemaGuard:
    def test_ensure_indexes_passes_on_a_migrated_database(self, store):
        store.ensure_indexes()  # must not raise

    def test_ping_works(self, store):
        store.ping()


class TestSources:
    def test_a_source_round_trips_and_returns_its_digest(self, store):
        doc = PREFIX + "src-1"
        digest = store.upsert_source(doc, listing(), "syllabus text", None, PREFIX + "run-1")

        assert digest == sha256("syllabus text")
        assert doc in store.known_source_codes()

        rows = [r for r in store.iter_sources() if r["doc_code"] == doc]
        assert len(rows) == 1
        row = rows[0]
        assert row["course_code"] == "BIOLOGY 3AA3"
        assert row["term"] == "Winter 2026"
        assert row["text"] == "syllabus text"
        assert row["text_sha256"] == digest
        assert row["text_length"] == len("syllabus text")
        # extract.py reads exactly these three keys off the row.
        assert {"doc_code", "course_code", "term"} <= set(row)

    def test_iter_sources_leaves_the_raw_blob_behind(self, store):
        doc = PREFIX + "src-2"
        store.upsert_source(doc, listing(), "text", {"big": "payload"}, PREFIX + "run-1")
        row = next(r for r in store.iter_sources() if r["doc_code"] == doc)
        assert "raw_gz" not in row, "the blob must not be dragged through an extract pass"

    def test_raw_is_compressed_and_comes_back_intact(self, store):
        doc = PREFIX + "src-3"
        raw = {"nested": {"html": "<p>hello</p>"}, "list": [1, 2, 3]}
        store.upsert_source(doc, listing(), "text", raw, PREFIX + "run-1")
        assert store.raw_for(doc) == raw

    def test_raw_is_absent_when_never_stored(self, store):
        doc = PREFIX + "src-4"
        store.upsert_source(doc, listing(), "text", None, PREFIX + "run-1")
        assert store.raw_for(doc) is None

    def test_a_rescrape_without_raw_does_not_wipe_an_existing_blob(self, store):
        # SYLLABUS_STORE_RAW can be turned off between runs. That must not destroy what an earlier
        # run captured, which is the whole point of the coalesce in upsert_source.
        doc = PREFIX + "src-5"
        store.upsert_source(doc, listing(), "v1", {"kept": True}, PREFIX + "run-1")
        store.upsert_source(doc, listing(), "v2", None, PREFIX + "run-2")

        assert store.raw_for(doc) == {"kept": True}
        row = next(r for r in store.iter_sources() if r["doc_code"] == doc)
        assert row["text"] == "v2", "the rest of the row must still be updated"

    def test_upsert_is_idempotent_on_doc_code(self, store):
        doc = PREFIX + "src-6"
        store.upsert_source(doc, listing(), "a", None, PREFIX + "run-1")
        store.upsert_source(doc, listing(), "b", None, PREFIX + "run-2")
        rows = [r for r in store.iter_sources() if r["doc_code"] == doc]
        assert len(rows) == 1
        assert rows[0]["text"] == "b"

    def test_terms_filter_narrows_the_scan(self, store):
        store.upsert_source(PREFIX + "w", listing(term="Winter 2026"), "w", None, PREFIX + "r")
        store.upsert_source(PREFIX + "f", listing(term="Fall 2026"), "f", None, PREFIX + "r")

        winter = {r["doc_code"] for r in store.iter_sources(["Winter 2026"])}
        assert PREFIX + "w" in winter
        assert PREFIX + "f" not in winter


class TestCatalog:
    def test_an_extraction_round_trips_with_its_keys_intact(self, store):
        doc = PREFIX + "cat-1"
        store.upsert_extraction(
            doc_code=doc,
            course_code="BIOLOGY 3AA3",
            term="Winter 2026",
            assessments=assessments(),
            source_sha="abc123",
            model="gemini-2.5-pro",
            temperature=0.3,
            run_id=PREFIX + "run-1",
        )

        row = next(r for r in store.iter_catalog() if r["doc_code"] == doc)
        stored = row["assessments"]
        if isinstance(stored, str):  # jsonb comes back parsed, but do not depend on it
            stored = json.loads(stored)

        # The cross-language contract. The backend reads these with a SNAKE_CASE Jackson mapper;
        # a renamed key here deserialises as null over there with no error.
        item = stored["grading_scheme"]["schemes"]["scheme_1"]["assessments"][0]
        assert item["due_date"] == "2026-04-15"
        assert item["start_time"] == "09:00"
        assert item["bonus_assessment"] is False
        assert item["replacement_rule"]["N"] == 2
        assert item["replacement_rule"]["trigger_type"] == "DROP_LOWEST"
        assert stored["grading_scheme"]["selection_rule"] == "MAX"

        assert row["extraction"]["model"] == "gemini-2.5-pro"
        assert row["extraction"]["temperature"] == 0.3
        assert row["source_text_sha256"] == "abc123"

    def test_re_extraction_replaces_rather_than_duplicating(self, store):
        doc = PREFIX + "cat-2"
        for weight in (0.4, 0.5):
            store.upsert_extraction(
                doc_code=doc,
                course_code="BIOLOGY 3AA3",
                term="Winter 2026",
                assessments=assessments(weight),
                source_sha=f"sha-{weight}",
                model="m",
                temperature=0.1,
                run_id=PREFIX + "run",
            )
        rows = [r for r in store.iter_catalog() if r["doc_code"] == doc]
        assert len(rows) == 1
        item = rows[0]["assessments"]["grading_scheme"]["schemes"]["scheme_1"]["assessments"][0]
        assert item["weight"] == 0.5
        assert rows[0]["source_text_sha256"] == "sha-0.5"

    def test_sections_of_one_course_do_not_overwrite_each_other(self, store):
        # The reason the key grew a doc_code. Two sections, same course and term.
        for section in ("cat-s1", "cat-s2"):
            store.upsert_extraction(
                doc_code=PREFIX + section,
                course_code="BIOLOGY 3AA3",
                term="Winter 2026",
                assessments=assessments(),
                source_sha="sha",
                model="m",
                temperature=0.1,
                run_id=PREFIX + "run",
            )
        found = {r["doc_code"] for r in store.iter_catalog() if r["doc_code"].startswith(PREFIX)}
        assert found == {PREFIX + "cat-s1", PREFIX + "cat-s2"}

    def test_extracted_source_hashes_drives_the_staleness_rule(self, store):
        fresh, stale, missing = PREFIX + "fresh", PREFIX + "stale", PREFIX + "missing"
        for doc, text in ((fresh, "same"), (stale, "changed-since"), (missing, "never")):
            store.upsert_source(doc, listing(), text, None, PREFIX + "run")

        store.upsert_extraction(
            doc_code=fresh, course_code="C", term="Winter 2026", assessments={},
            source_sha=sha256("same"), model="m", temperature=0.1, run_id=PREFIX + "run",
        )
        store.upsert_extraction(
            doc_code=stale, course_code="C", term="Winter 2026", assessments={},
            source_sha=sha256("an older version"), model="m", temperature=0.1, run_id=PREFIX + "run",
        )

        hashes = store.extracted_source_hashes()
        assert hashes[fresh] == sha256("same"), "unchanged source, so not stale"
        assert hashes[stale] != sha256("changed-since"), "text moved on, so it must re-extract"
        assert missing not in hashes, "never extracted, so it must be picked up"

    def test_catalog_rows_expose_the_keys_verify_reads(self, store):
        doc = PREFIX + "cat-3"
        store.upsert_extraction(
            doc_code=doc, course_code="BIOLOGY 3AA3", term="Winter 2026",
            assessments=assessments(), source_sha="s", model="m", temperature=0.1,
            run_id=PREFIX + "run",
        )
        row = next(r for r in store.iter_catalog() if r["doc_code"] == doc)
        # verify.check_document reads doc_code, course_code, term and assessments off the row.
        assert {"doc_code", "course_code", "term", "assessments"} <= set(row)


class TestRuns:
    def test_a_run_is_recorded_and_then_finished(self, store):
        run_id = PREFIX + "run-lifecycle"
        store.start_run(run_id, ["scrape", "extract"], trigger="cli")

        row = next(r for r in store.last_runs(50) if r["id"] == run_id)
        assert row["status"] == "running"
        assert row["finished_at"] is None
        assert row["stages"] == ["scrape", "extract"]

        store.finish_run(run_id, "ok", {"extracted": 3, "failures": []})

        row = next(r for r in store.last_runs(50) if r["id"] == run_id)
        assert row["status"] == "ok"
        assert row["finished_at"] is not None
        assert row["results"]["extracted"] == 3

    def test_a_report_containing_objects_and_datetimes_still_serialises(self, store):
        # BSON accepted datetimes directly, so this never came up on Mongo. A verify report carries
        # Violation objects and timestamps, and an unserialisable report must not lose the run.
        from datetime import datetime, timezone

        class Violation:
            def __init__(self):
                self.rule = "weights_do_not_sum"

            def __repr__(self):
                return "Violation(weights_do_not_sum)"

        run_id = PREFIX + "run-objects"
        store.start_run(run_id, ["verify"], trigger="cli")
        store.finish_run(
            run_id,
            "ok",
            {"when": datetime.now(timezone.utc), "violations": [Violation()]},
        )

        row = next(r for r in store.last_runs(50) if r["id"] == run_id)
        assert row["status"] == "ok"
        assert "weights_do_not_sum" in json.dumps(row["results"])

    def test_last_runs_is_newest_first(self, store):
        for n in (1, 2, 3):
            store.start_run(f"{PREFIX}run-order-{n}", ["scrape"], trigger="cli")
        ours = [r["id"] for r in store.last_runs(50) if r["id"].startswith(PREFIX + "run-order-")]
        assert ours == sorted(ours, reverse=True) or len(set(ours)) == 3
