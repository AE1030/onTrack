"""Tests for the pure parts of scrape and config: no network, no database."""

from datetime import date

import pytest

from syllabus_pipeline.config import CadenceConfig, ConfigError, _dsn_from_jdbc
from syllabus_pipeline.scrape import (
    ScrapeError,
    SimpleSyllabusClient,
    course_code_from_title,
    flatten_document,
)


class TestCourseCode:
    @pytest.mark.parametrize(
        "title,expected",
        [
            ("BIOLOGY 3AA3 C01_TBD", "BIOLOGY 3AA3"),
            ("SOCSCI 1SS3 C05", "SOCSCI 1SS3"),
            ("INDIGST 4T06B C01", "INDIGST 4T06B"),
            ("MATH 1ZA3", "MATH 1ZA3"),
            ("biology 3ep3s c01", "BIOLOGY 3EP3S"),
        ],
    )
    def test_subjects_longer_than_four_characters_parse(self, title, expected):
        # The original scraper's regex was [A-Z0-9]{4}\s[A-Z0-9]{4}, which returned None for
        # every subject that is not exactly four characters, and most of them are not. The
        # extract step then quietly re-derived the code with a different, looser regex, so
        # the two stages disagreed about what a course was called.
        assert course_code_from_title(title) == expected

    @pytest.mark.parametrize("title", ["", "   ", None, "12345"])
    def test_unparseable_titles_return_none(self, title):
        assert course_code_from_title(title) is None


class TestFlatten:
    def test_html_anywhere_in_the_tree_is_found(self):
        raw = {
            "sections": [
                {"body": "<p>Midterm worth 20%</p>"},
                {"nested": {"body": "<div>Final worth 40%</div>"}},
            ],
            "title": "not html",
        }
        text = flatten_document(raw)
        assert "Midterm worth 20%" in text
        assert "Final worth 40%" in text

    def test_repeated_blocks_appear_once(self):
        raw = {"a": "<p>Same</p>", "b": "<p>Same</p>"}
        assert flatten_document(raw).count("Same") == 1

    def test_script_and_style_are_stripped(self):
        raw = {"a": "<div>Keep<script>drop()</script><style>.x{}</style></div>"}
        text = flatten_document(raw)
        assert "Keep" in text
        assert "drop()" not in text

    def test_no_html_yields_nothing(self):
        assert flatten_document({"a": "plain", "b": 3}) == ""


class TestListingValidation:
    """A changed endpoint has to raise, not return an empty list.

    "scraped 0 new documents" and "the API moved" look identical in a log, and only one of
    them is fine.
    """

    def test_missing_items_raises(self):
        with pytest.raises(ScrapeError, match="no 'items' list"):
            SimpleSyllabusClient._absorb({"pagination": {"total": 5}}, {})

    def test_items_are_deduplicated_by_code(self):
        collected = {}
        SimpleSyllabusClient._absorb(
            {"items": [{"code": "a1", "title": "MATH 1ZA3"}, {"code": "a1", "title": "MATH 1ZA3"}]},
            collected,
        )
        assert list(collected) == ["a1"]

    def test_items_without_a_code_are_ignored(self):
        collected = {}
        SimpleSyllabusClient._absorb(
            {"items": [{"title": "MATH 1ZA3"}, {"code": "b2", "title": "PHYSICS 1D03"}]},
            collected,
        )
        assert list(collected) == ["b2"]


class TestMissingDocument:
    def test_an_empty_envelope_is_gone_not_a_failure(self):
        """There is no 404 on this endpoint.

        A code the site does not know answers 200 with {"pagination": {"total": 0},
        "items": []}. Verified against an invented code. Treating that as a fetch failure
        would put withdrawn sections in the run report next to genuine network faults.
        """
        from syllabus_pipeline.config import ScrapeConfig
        from syllabus_pipeline.scrape import DocumentGone

        class FakeResponse:
            status_code = 200
            headers: dict = {}

            @staticmethod
            def json():
                return {
                    "sys": {"success": True},
                    "pagination": {"total": 0, "returned": 0},
                    "info": [],
                    "items": [],
                }

            @staticmethod
            def raise_for_status():
                return None

        class FakeSession:
            headers: dict = {}

            def get(self, *_args, **_kwargs):
                return FakeResponse()

            def close(self):
                pass

        config = ScrapeConfig(
            base_url="https://example.test",
            term_statuses=("current",),
            user_agent="test",
            page_size=50,
            request_delay_sec=0,
            timeout_sec=5,
            max_retries=5,
            max_new_docs=None,
            store_raw=False,
        )
        client = SimpleSyllabusClient(config)
        client.session = FakeSession()

        with pytest.raises(DocumentGone, match="no longer published"):
            client.full_document("zzzzzzzzz")


class TestBlockHandling:
    def test_403_is_not_retried(self):
        """A CDN 403 is a decision about the client, not a transient fault.

        Retrying it five times per document turns one refused request into five, several
        hundred times over, which is how an edge rule that refuses a client becomes one that
        bans an address.
        """
        from syllabus_pipeline.config import ScrapeConfig
        from syllabus_pipeline.scrape import BlockedError

        calls = {"n": 0}

        class FakeResponse:
            status_code = 403
            headers = {"server": "CloudFront"}

        class FakeSession:
            headers: dict = {}

            def get(self, *_args, **_kwargs):
                calls["n"] += 1
                return FakeResponse()

            def close(self):
                pass

        config = ScrapeConfig(
            base_url="https://example.test",
            term_statuses=("current",),
            user_agent="test",
            page_size=50,
            request_delay_sec=0,
            timeout_sec=5,
            max_retries=5,
            max_new_docs=None,
            store_raw=False,
        )
        client = SimpleSyllabusClient(config)
        client.session = FakeSession()

        with pytest.raises(BlockedError, match="CloudFront"):
            client.full_document("abc123")

        assert calls["n"] == 1, "a 403 must cost exactly one request, not max_retries"


class TestCadence:
    """One daily Scheduler trigger, turned into daily-then-weekly here."""

    def setup_method(self):
        # Monday 5 January 2026.
        self.cadence = CadenceConfig(
            term_start=date(2026, 1, 5), daily_window_days=14, weekly_weekday=0
        )

    def test_before_the_term_nothing_runs(self):
        due, reason = self.cadence.should_run(date(2026, 1, 1))
        assert not due
        assert "not started" in reason

    def test_inside_the_window_every_day_runs(self):
        for day in range(1, 15):
            due, _ = self.cadence.should_run(date(2026, 1, 4 + day))
            assert due, f"day {day} should run"

    def test_after_the_window_only_the_weekly_day_runs(self):
        assert self.cadence.should_run(date(2026, 1, 19))[0] is True  # Monday
        assert self.cadence.should_run(date(2026, 1, 20))[0] is False  # Tuesday
        assert self.cadence.should_run(date(2026, 1, 26))[0] is True  # Monday

    def test_no_term_start_means_always_due(self):
        always = CadenceConfig(term_start=None, daily_window_days=14, weekly_weekday=0)
        assert always.should_run(date(2026, 7, 4))[0] is True


class TestDsnFromJdbc:
    """The backend defines DB_URL as a JDBC url, which psycopg2 cannot parse.

    Translating it here means the pipeline runs off the same three environment variables the
    backend already has, instead of a fourth that can drift out of step with them.
    """

    def test_a_plain_jdbc_url_becomes_a_libpq_dsn(self):
        dsn = _dsn_from_jdbc("jdbc:postgresql://localhost:5432/tracker_db", "postgres", "secret")
        assert dsn == "dbname=tracker_db host=localhost port=5432 user=postgres password=secret"

    def test_the_result_is_keyword_form_not_a_uri(self):
        # libpq accepts a URI or keyword/value pairs, never a URI with keywords appended. Getting
        # that wrong fails at connect time with "no password supplied", which points nowhere near
        # the cause.
        dsn = _dsn_from_jdbc("jdbc:postgresql://h:5432/db", "u", "p")
        assert not dsn.startswith("postgres")
        assert dsn.startswith("dbname=")

    def test_credentials_are_keywords_not_uri_parts(self):
        # A password with @ or / in it would corrupt a URI that embedded it. Keyword form needs no
        # percent-encoding at all.
        dsn = _dsn_from_jdbc("jdbc:postgresql://h:5432/db", "u", "p@ss/word:1")
        assert "password=p@ss/word:1" in dsn

    def test_a_value_containing_whitespace_is_quoted(self):
        # libpq splits on whitespace, so an unquoted space would silently truncate the password.
        dsn = _dsn_from_jdbc("jdbc:postgresql://h:5432/db", "u", "pa ss")
        assert "password='pa ss'" in dsn

    def test_a_host_may_be_absent(self):
        # jdbc:postgresql:///db is the Cloud SQL socket-factory shape the old production url used.
        dsn = _dsn_from_jdbc("jdbc:postgresql:///tracker_db", "u", "p")
        assert "host=" not in dsn
        assert dsn.startswith("dbname=tracker_db")

    def test_jdbc_query_parameters_are_dropped(self):
        # cloudSqlInstance and socketFactory are JDBC's; libpq rejects the whole DSN if they are
        # passed through. This is exactly the shape the old production DB_URL had.
        dsn = _dsn_from_jdbc(
            "jdbc:postgresql:///tracker_db?cloudSqlInstance=p:r:i"
            "&socketFactory=com.google.cloud.sql.postgres.SocketFactory",
            "u",
            "p",
        )
        assert "cloudSqlInstance" not in dsn
        assert "socketFactory" not in dsn

    def test_missing_credentials_are_simply_omitted(self):
        # A local trust-auth setup needs neither, and an empty keyword is a syntax error.
        dsn = _dsn_from_jdbc("jdbc:postgresql://localhost:5432/tracker_db", "", "")
        assert dsn == "dbname=tracker_db host=localhost port=5432"

    @pytest.mark.parametrize(
        "url",
        [
            "jdbc:postgresql://localhost:5432",
            "jdbc:postgresql://localhost:5432/",
            "jdbc:postgresql://h/?x=1",
        ],
    )
    def test_a_url_with_no_database_fails_at_startup(self, url):
        # psycopg2 would raise this on the first query instead. Failing here, with the variable
        # named, is the difference between a five-second fix and a stack trace.
        with pytest.raises(ConfigError):
            _dsn_from_jdbc(url, "u", "p")

    @pytest.mark.parametrize(
        "url",
        ["mongodb://127.0.0.1:27017/OnTrack", "postgresql://h/db", "jdbc:mysql://h:3306/db"],
    )
    def test_a_non_postgres_jdbc_url_is_refused(self, url):
        with pytest.raises(ConfigError):
            _dsn_from_jdbc(url, "u", "p")
