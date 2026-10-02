"""One entrypoint for all three stages.

    python -m syllabus_pipeline run                 scrape, extract, verify
    python -m syllabus_pipeline scrape              refresh sources only
    python -m syllabus_pipeline extract             re-extract anything stale
    python -m syllabus_pipeline verify              check the catalog, change nothing
    python -m syllabus_pipeline status              what the last few runs did

Exit codes matter here, because in Cloud Run they are the alert:

    0   ran, nothing wrong
    1   a stage failed outright, or verify found errors and FAIL_ON_VIOLATIONS is set
    2   misconfigured, nothing ran
    3   skipped by the cadence rule, nothing to do today
"""

from __future__ import annotations

import argparse
import logging
import re
import sys
import uuid
from datetime import date, datetime, timezone
from typing import Any, Optional

from . import config as config_module
from . import extract, scrape, verify
from .store import Store

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_MISCONFIGURED = 2
EXIT_SKIPPED = 3

ALL_STAGES = ("scrape", "extract", "verify")

log = logging.getLogger("syllabus_pipeline")


def _configure_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        stream=sys.stdout,
    )
    # These two are chatty at INFO and say nothing the pipeline's own logging does not.
    logging.getLogger("psycopg2").setLevel(logging.WARNING)
    logging.getLogger("urllib3").setLevel(logging.WARNING)


def _parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="syllabus_pipeline", description=__doc__)
    parser.add_argument(
        "command",
        choices=["run", "scrape", "extract", "verify", "status"],
        help="which stage, or 'run' for all three",
    )
    parser.add_argument(
        "--terms",
        default=None,
        help="comma-separated term names to limit extract and verify to, "
             "for example 'Winter 2026'. Scrape is governed by SYLLABUS_TERM_STATUSES.",
    )
    parser.add_argument(
        "--respect-cadence",
        action="store_true",
        help="exit 3 without doing anything if TERM_START_DATE says today is not a run day. "
             "Cloud Scheduler triggers daily and this decides whether that trigger is due.",
    )
    parser.add_argument(
        "--ignore-violations",
        action="store_true",
        help="report verify errors but still exit 0. Overrides FAIL_ON_VIOLATIONS.",
    )
    parser.add_argument("--verbose", action="store_true")
    return parser.parse_args(argv)


def _safe_dsn(dsn: str) -> str:
    """The DSN with any password removed, for logging.

    The DSN is assembled from DB_PASSWORD, so logging it raw would put the database password in
    Cloud Logging on every run.
    """
    return re.sub(r"password=('(?:[^'\\]|\\.)*'|\S+)", "password=***", dsn)


def _stages_for(command: str) -> list[str]:
    return list(ALL_STAGES) if command == "run" else [command]


def _status(store: Store) -> int:
    runs = store.last_runs(10)
    if not runs:
        print("no runs recorded yet")
        return EXIT_OK

    print(f"{'started':20}  {'status':9}  {'stages':22}  summary")
    for run_doc in runs:
        started = run_doc.get("started_at")
        started_text = started.strftime("%Y-%m-%d %H:%M:%S") if started else "?"
        results = run_doc.get("results") or {}
        parts = []
        if "scrape" in results:
            parts.append(f"fetched {results['scrape'].get('fetched', 0)}")
        if "extract" in results:
            parts.append(f"extracted {results['extract'].get('extracted', 0)}")
        if "verify" in results:
            parts.append(
                f"{results['verify'].get('errors', 0)} error(s) "
                f"over {results['verify'].get('checked', 0)}"
            )
        print(
            f"{started_text:20}  {run_doc.get('status', '?'):9}  "
            f"{','.join(run_doc.get('stages', [])):22}  {', '.join(parts)}"
        )
    return EXIT_OK


def main(argv: Optional[list[str]] = None) -> int:
    args = _parse_args(argv)
    _configure_logging(args.verbose)

    stages = _stages_for(args.command)
    needs_gemini = "extract" in stages

    try:
        cfg = config_module.load(require_gemini=needs_gemini)
    except config_module.ConfigError as exc:
        log.error("%s", exc)
        return EXIT_MISCONFIGURED

    terms = (
        [t.strip() for t in args.terms.split(",") if t.strip()] if args.terms else None
    )

    if args.respect_cadence and args.command != "status":
        due, reason = cfg.cadence.should_run(date.today())
        log.info("cadence: %s", reason)
        if not due:
            return EXIT_SKIPPED

    run_id = f"{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}-{uuid.uuid4().hex[:8]}"

    try:
        store = Store(cfg.postgres.dsn)
    except Exception as exc:  # noqa: BLE001 - surfaced as a config problem
        log.error("could not connect to Postgres: %s", exc)
        return EXIT_MISCONFIGURED

    with store:
        try:
            store.ping()
        except Exception as exc:  # noqa: BLE001
            log.error("cannot reach Postgres at the configured DSN: %s", exc)
            return EXIT_MISCONFIGURED

        log.info("connected to %s", _safe_dsn(cfg.postgres.dsn))

        if args.command == "status":
            return _status(store)

        store.ensure_indexes()
        store.start_run(run_id, stages, trigger="cli")
        log.info("run %s: %s", run_id, ", ".join(stages))

        results: dict[str, Any] = {}
        status = "ok"
        exit_code = EXIT_OK

        try:
            if "scrape" in stages:
                results["scrape"] = scrape.run(store, cfg.scrape, run_id)
                summary = results["scrape"]
                log.info(
                    "scrape: %d listed, %d fetched, %d failed",
                    summary["listed"],
                    summary["fetched"],
                    len(summary["failures"]),
                )
                # A blocked scrape is not a partial success. Nothing was fetched and nothing
                # will be until someone changes something, so it has to be the loud kind of
                # failure: in Cloud Run the exit code is the only alert there is.
                if summary.get("blocked"):
                    log.error(
                        "scrape was refused at the edge and fetched nothing. "
                        "The catalog is now as stale as whenever this last worked."
                    )
                    status = "failed"
                    exit_code = EXIT_FAILED
                elif summary["failures"]:
                    status = "partial"

            if "extract" in stages:
                results["extract"] = extract.run(store, cfg.extract, run_id, terms)
                summary = results["extract"]
                log.info(
                    "extract: %d extracted, %d failed",
                    summary["extracted"],
                    len(summary["failures"]),
                )
                if summary["failures"]:
                    status = "partial"

            if "verify" in stages:
                results["verify"] = verify.run(store, terms)
                summary = results["verify"]
                for line in verify.format_summary(summary):
                    log.info("verify: %s", line)

                fail_on = cfg.fail_on_violations and not args.ignore_violations
                if summary["errors"] and fail_on:
                    status = "failed"
                    exit_code = EXIT_FAILED
                elif summary["errors"]:
                    status = "partial" if status == "ok" else status

        except Exception as exc:  # noqa: BLE001 - recorded before it propagates out
            log.exception("run %s died", run_id)
            results["fatal"] = f"{type(exc).__name__}: {exc}"
            store.finish_run(run_id, "failed", results)
            return EXIT_FAILED

        store.finish_run(run_id, status, results)
        log.info("run %s finished: %s", run_id, status)
        return exit_code


if __name__ == "__main__":
    sys.exit(main())
