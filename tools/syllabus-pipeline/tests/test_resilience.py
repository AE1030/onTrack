"""What the extract stage does when Gemini or Postgres goes wrong mid-run.

Written after the 2026-10-24 run: Neon suspended the compute during a streak of extractions
with nothing to write, the store never reconnected, and the stage kept paying Gemini for 421
answers it could not save, then ran out of credits on the retry. Every case below is one of
those failure modes, with a fake Gemini client and fake connections, so none of it needs the
network or a database.
"""

from __future__ import annotations

import psycopg2
import pytest
from google.genai import errors as genai_errors

from syllabus_pipeline import extract, store as store_module
from syllabus_pipeline.config import ExtractConfig
from syllabus_pipeline.extract import (
    ExtractionAborted,
    ExtractionError,
    GeminiExtractor,
    NoGradingScheme,
)

GOOD = (
    '{"grading_scheme": {"selection_rule": null, "schemes": {"scheme_1": {"label": null, '
    '"assessments": [{"name": "Final", "category": "EXAM", "weight": 100}]}}}}'
)
NO_SCHEME = '{"grading_scheme": {"selection_rule": null, "schemes": {}}}'


def config(**overrides):
    fields = dict(
        api_key="test",
        model="gemini-test",
        temperature=0.3,
        thinking_budget=0,
        max_docs_per_run=None,
        request_delay_sec=0,
        max_retries=3,
        no_scheme_attempts=2,
    )
    fields.update(overrides)
    return ExtractConfig(**fields)


def api_error(code, status="ERROR"):
    return genai_errors.APIError(code, {"error": {"code": code, "message": "x", "status": status}})


@pytest.fixture(autouse=True)
def no_sleep(monkeypatch):
    monkeypatch.setattr(extract.time, "sleep", lambda _s: None)


# -------------------------------------------------------------- the Gemini retry policy


class FakeModels:
    """Answers generate_content from a script: a string is the response text, else raised."""

    def __init__(self, script):
        self.script = list(script)
        self.calls = 0

    def generate_content(self, **_kwargs):
        self.calls += 1
        step = self.script.pop(0) if len(self.script) > 1 else self.script[0]
        if isinstance(step, Exception):
            raise step
        return type("Response", (), {"text": step})()


def extractor(script, **cfg):
    e = GeminiExtractor.__new__(GeminiExtractor)
    e.config = config(**cfg)
    e.client = type("Client", (), {"models": FakeModels(script)})()
    return e


def calls(e):
    return e.client.models.calls


def test_a_good_answer_is_one_call():
    e = extractor([GOOD])
    assert e.extract("text")["grading_scheme"]["schemes"]
    assert calls(e) == 1


def test_no_scheme_gets_two_attempts_not_three():
    e = extractor([NO_SCHEME])
    with pytest.raises(NoGradingScheme):
        e.extract("text")
    assert calls(e) == 2


def test_no_scheme_then_a_scheme_is_a_success():
    e = extractor([NO_SCHEME, GOOD])
    assert e.extract("text")
    assert calls(e) == 2


@pytest.mark.parametrize("code", [401, 402, 403, 404])
def test_an_account_level_refusal_aborts_after_one_call(code):
    e = extractor([api_error(code, "RESOURCE_EXHAUSTED")])
    with pytest.raises(ExtractionAborted, match=str(code)):
        e.extract("text")
    assert calls(e) == 1


def test_a_document_level_400_fails_that_document_without_retrying():
    e = extractor([api_error(400, "INVALID_ARGUMENT")])
    with pytest.raises(ExtractionError, match="rejected the document"):
        e.extract("text")
    assert calls(e) == 1


@pytest.mark.parametrize("code", [429, 500, 503])
def test_transient_gemini_errors_are_retried(code):
    e = extractor([api_error(code), api_error(code), GOOD])
    assert e.extract("text")
    assert calls(e) == 3


def test_transient_errors_give_up_after_max_retries():
    e = extractor([api_error(503)])
    with pytest.raises(ExtractionError, match="after 3 attempts"):
        e.extract("text")
    assert calls(e) == 3


# -------------------------------------------------------------- the stage


class FakeStore:
    def __init__(self, sources, no_scheme=None, extracted=None, fail_upsert=None):
        self.sources = sources
        self.no_scheme = dict(no_scheme or {})
        self.extracted = dict(extracted or {})
        self.fail_upsert = fail_upsert or {}
        self.saved = []
        self.recorded = []

    def extracted_source_hashes(self):
        return self.extracted

    def no_scheme_hashes(self):
        return self.no_scheme

    def iter_sources(self, _terms=None):
        return iter(self.sources)

    def upsert_extraction(self, doc_code, **_kwargs):
        if doc_code in self.fail_upsert:
            raise self.fail_upsert[doc_code]
        self.saved.append(doc_code)

    def record_no_scheme(self, doc_code, **_kwargs):
        self.recorded.append(doc_code)


def source(doc_code, sha="h"):
    return {
        "doc_code": doc_code,
        "course_code": "TEST 1A03",
        "term": "Fall 2026",
        "text": doc_code,
        "text_sha256": sha,
    }


def scripted_extractor(monkeypatch, outcomes):
    """Patch the stage's extractor: outcomes maps doc_code (the source text) to a result."""
    seen = []

    class Fake:
        def __init__(self, _config):
            pass

        def extract(self, text):
            seen.append(text)
            outcome = outcomes.get(text, "ok")
            if isinstance(outcome, Exception):
                raise outcome
            return {"grading_scheme": {"selection_rule": None, "schemes": {"s": {}}}}

    monkeypatch.setattr(extract, "GeminiExtractor", Fake)
    return seen


def test_no_scheme_is_recorded_and_is_not_a_failure(monkeypatch):
    scripted_extractor(monkeypatch, {"b": NoGradingScheme("none")})
    fake = FakeStore([source("a"), source("b"), source("c")])
    summary = extract.run(fake, config(), "run")
    assert fake.saved == ["a", "c"]
    assert fake.recorded == ["b"]
    assert summary["no_scheme"] == 1
    assert summary["failures"] == []
    assert summary["aborted"] is None


def test_a_recorded_no_scheme_source_is_skipped_until_its_text_changes(monkeypatch):
    fake = FakeStore(
        [source("same", sha="h1"), source("edited", sha="h2-new")],
        no_scheme={"same": "h1", "edited": "h2-old"},
    )
    assert [s["doc_code"] for s in extract.pending(fake)] == ["edited"]


def test_out_of_credits_stops_the_stage_and_keeps_what_was_saved(monkeypatch):
    seen = scripted_extractor(monkeypatch, {"b": ExtractionAborted("402 RESOURCE_EXHAUSTED")})
    fake = FakeStore([source("a"), source("b"), source("c"), source("d")])
    summary = extract.run(fake, config(), "run")
    assert fake.saved == ["a"]
    assert seen == ["a", "b"], "nothing after the 402 may be sent to Gemini"
    assert "402" in summary["aborted"]


def test_a_database_that_stays_down_stops_the_stage(monkeypatch):
    seen = scripted_extractor(monkeypatch, {})
    fake = FakeStore(
        [source("a"), source("b"), source("c")],
        fail_upsert={"b": psycopg2.OperationalError("SSL connection has been closed")},
    )
    summary = extract.run(fake, config(), "run")
    assert fake.saved == ["a"]
    assert seen == ["a", "b"], "no Gemini call may follow an unsaveable answer"
    assert "Postgres unreachable" in summary["aborted"]


def test_one_bad_row_fails_that_document_only(monkeypatch):
    scripted_extractor(monkeypatch, {})
    fake = FakeStore(
        [source("a"), source("b"), source("c")],
        fail_upsert={"b": psycopg2.DataError("value too long")},
    )
    summary = extract.run(fake, config(), "run")
    assert fake.saved == ["a", "c"]
    assert [f["doc_code"] for f in summary["failures"]] == ["b"]
    assert summary["aborted"] is None


# -------------------------------------------------------------- the store reconnects


class FakeCursor:
    def __init__(self, conn):
        self.conn = conn

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, _sql, _params=None):
        if self.conn.dead:
            # What psycopg2 does when Neon has suspended the compute under it.
            self.conn.closed = 2
            raise psycopg2.OperationalError("SSL connection has been closed unexpectedly")
        self.conn.statements += 1

    def fetchone(self):
        return {"?column?": 1}


class FakeConn:
    def __init__(self, dead):
        self.dead = dead
        self.closed = 0
        self.autocommit = False
        self.statements = 0

    def cursor(self, cursor_factory=None):
        if self.closed:
            raise psycopg2.InterfaceError("connection already closed")
        return FakeCursor(self)

    def close(self):
        self.closed = 1


@pytest.fixture
def connections(monkeypatch):
    """Each connect() hands out the next connection; the first one is already dead."""
    made = []
    plan = [True, False, False]

    def connect(_dsn, connect_timeout=None):
        conn = FakeConn(dead=plan[len(made)])
        made.append(conn)
        return conn

    monkeypatch.setattr(store_module.psycopg2, "connect", connect)
    return made


def test_a_dropped_connection_is_replaced_and_the_statement_retried(connections):
    s = store_module.Store("dsn")
    s.ping()
    assert len(connections) == 2
    assert connections[1].statements == 1
    assert connections[1].autocommit is True


def test_a_connection_already_known_closed_is_replaced(connections):
    s = store_module.Store("dsn")
    connections[0].closed = 2  # psycopg2 marked it closed on an earlier failure
    s.ping()
    assert len(connections) == 2


def test_a_failing_statement_on_a_live_connection_is_not_retried(monkeypatch, connections):
    s = store_module.Store("dsn")
    s._connect()  # move onto a healthy connection
    healthy = connections[-1]

    def boom(_sql, _params=None):
        raise psycopg2.OperationalError("canceling statement due to statement timeout")

    monkeypatch.setattr(FakeCursor, "execute", lambda self, sql, params=None: boom(sql))
    with pytest.raises(psycopg2.OperationalError, match="statement timeout"):
        s.ping()
    assert connections[-1] is healthy, "a live connection must not be thrown away"
