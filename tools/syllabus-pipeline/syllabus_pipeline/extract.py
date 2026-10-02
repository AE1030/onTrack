"""Stage 2: turn stored source text into catalog documents via Gemini.

Two things the old script did not do.

First, it only ever skipped work it had already done, using an append-only text file as the
record. That meant a syllabus edited mid-term was never re-read: the doc_code was in the
completed list, so the pipeline moved on. Staleness here is decided by comparing the source
text's sha256 against the hash recorded on the catalog entry, so an edited syllabus comes
back round on the next run without anyone deleting a line from a file.

Second, it wrote whatever JSON the model returned. Asked for the same field across a few
hundred syllabi, the model will answer with a boolean most of the time and the string "false"
the rest, and the same goes for every numeric field. The Java model declares Boolean, int and
Integer for three of them, and would be leaning on Spring's conversion service to absorb the
difference. `normalise` below is where the contract is enforced instead, before anything is
written, so the collection only ever holds one shape.
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any, Optional

from google import genai
from google.genai import types

from .config import ExtractConfig
from .prompts import SYSTEM_PROMPT, user_prompt
from .store import Store

log = logging.getLogger(__name__)

VALID_TRIGGER_TYPES = {"LOWEST_N_DROPPED"}


class ExtractionError(RuntimeError):
    """The model answered, but not with a document this pipeline can store."""


# ---------------------------------------------------------------- normalisation


def _as_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() == "true"
    return bool(value)


def _as_int(value: Any) -> Optional[int]:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value) if value.is_integer() else None
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            return None
    return None


def _as_float(value: Any) -> Optional[float]:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value.strip().rstrip("%"))
        except ValueError:
            return None
    return None


def _as_text(value: Any) -> Optional[str]:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _normalise_due_date(value: Any) -> Any:
    """A string, a list of strings, or null. Never a list of one, never an empty list.

    Java maps this onto a single String field. A list arrives there through Spring's
    collection-to-String converter, which joins on "," with no space -- different from the
    ", " the Jackson path produces for user uploads. Collapsing a single-element list here
    means the common case never takes that road at all.
    """
    if value is None:
        return None
    if isinstance(value, list):
        dates = [_as_text(item) for item in value]
        dates = [d for d in dates if d]
        if not dates:
            return None
        return dates[0] if len(dates) == 1 else dates
    return _as_text(value)


def _normalise_assessment(raw: Any) -> Optional[dict[str, Any]]:
    if not isinstance(raw, dict):
        return None

    name = _as_text(raw.get("name"))
    if not name:
        # An assessment with no name cannot be shown, matched to a grade, or explained to a
        # student. Dropping it is better than storing a blank row the UI has to special-case.
        return None

    bonus = _as_bool(raw.get("bonus_assessment"))
    bonus_description = _as_text(raw.get("bonus_description"))
    weight = _as_float(raw.get("weight"))

    # The prompt states bonus assessments carry weight 0 so they cannot skew a scheme total.
    # Enforced here rather than merely asked for, because a bonus that slips through with a
    # real weight silently inflates every grade projection for that course.
    if bonus:
        weight = 0.0
    elif bonus_description is not None:
        # A description without the flag is the model half-applying the rule. The description
        # is the more specific signal, so keep it and drop the contradiction.
        bonus_description = None

    replacement_rule = raw.get("replacement_rule")
    if isinstance(replacement_rule, dict):
        n = _as_int(replacement_rule.get("N"))
        trigger = _as_text(replacement_rule.get("trigger_type"))
        # A partial rule is worse than no rule: the calculator would drop a different number
        # of assessments than the syllabus says. The prompt forbids partials; this makes it so.
        if n is None or n < 0 or trigger not in VALID_TRIGGER_TYPES:
            replacement_rule = None
        else:
            replacement_rule = {"N": n, "trigger_type": trigger}
    else:
        replacement_rule = None

    occurrence_raw = raw.get("occurrence")
    total = _as_int(occurrence_raw.get("total")) if isinstance(occurrence_raw, dict) else None

    return {
        "name": name,
        "category": _as_text(raw.get("category")),
        "due_date": _normalise_due_date(raw.get("due_date")),
        "weight": weight,
        "start_time": _as_text(raw.get("start_time")),
        "end_time": _as_text(raw.get("end_time")),
        "location": _as_text(raw.get("location")),
        "description": _as_text(raw.get("description")),
        "bonus_assessment": bonus,
        "bonus_description": bonus_description,
        "replacement_rule": replacement_rule,
        "occurrence": {"total": total},
    }


def normalise(extraction: Any) -> dict[str, Any]:
    """Coerce the model's JSON into exactly the shape the Java model declares.

    Raises rather than returning an empty husk. A document with no usable scheme is a failed
    extraction, and storing it as a success is the difference between a catalog that is short a
    course and one that answers for that course with something the calculator cannot use. The
    first is visible in the run report; the second is not visible until a student sees a wrong
    grade. Failing here also leaves the source stale, so the next run tries it again.
    """
    if not isinstance(extraction, dict):
        raise ExtractionError(f"expected a JSON object, got {type(extraction).__name__}")

    grading_scheme = extraction.get("grading_scheme")
    if not isinstance(grading_scheme, dict):
        raise ExtractionError("no 'grading_scheme' object in the response")

    schemes_raw = grading_scheme.get("schemes")
    if not isinstance(schemes_raw, dict) or not schemes_raw:
        raise ExtractionError("'grading_scheme.schemes' is missing or empty")

    schemes: dict[str, Any] = {}
    for scheme_id, definition in schemes_raw.items():
        if not isinstance(definition, dict):
            continue
        items = definition.get("assessments")
        normalised_items = []
        if isinstance(items, list):
            for item in items:
                normalised = _normalise_assessment(item)
                if normalised is not None:
                    normalised_items.append(normalised)
        if not normalised_items:
            continue
        schemes[str(scheme_id)] = {
            "label": _as_text(definition.get("label")),
            "assessments": normalised_items,
        }

    if not schemes:
        raise ExtractionError("every scheme in the response was empty after normalisation")

    selection_rule = _as_text(grading_scheme.get("selection_rule"))
    # Java maps this onto an enum with a single MAX constant. Anything else fails to
    # deserialize the whole document, which would take out a course's syllabus entirely.
    if selection_rule != "MAX":
        selection_rule = "MAX" if len(schemes) > 1 else None

    return {"grading_scheme": {"selection_rule": selection_rule, "schemes": schemes}}


# ---------------------------------------------------------------- the model call


class GeminiExtractor:
    def __init__(self, config: ExtractConfig):
        self.config = config
        self.client = genai.Client(api_key=config.api_key)

    def extract(self, syllabus_text: str) -> dict[str, Any]:
        contents = [
            types.Content(
                role="user",
                parts=[types.Part.from_text(text=user_prompt(syllabus_text))],
            )
        ]
        generation_config = types.GenerateContentConfig(
            temperature=self.config.temperature,
            response_mime_type="application/json",
            thinking_config=types.ThinkingConfig(thinking_budget=self.config.thinking_budget),
            system_instruction=[types.Part.from_text(text=SYSTEM_PROMPT)],
        )

        last_error: Optional[Exception] = None
        for attempt in range(1, self.config.max_retries + 1):
            try:
                response = self.client.models.generate_content(
                    model=self.config.model,
                    contents=contents,
                    config=generation_config,
                )
                text = (response.text or "").strip()
                if not text:
                    raise ExtractionError("model returned an empty response")
                return normalise(json.loads(text))
            except Exception as exc:  # noqa: BLE001 - retried, then re-raised below
                last_error = exc
                if attempt == self.config.max_retries:
                    break
                wait = 2.0**attempt
                log.warning(
                    "extraction attempt %d/%d failed (%s), retrying in %.0fs",
                    attempt,
                    self.config.max_retries,
                    exc,
                    wait,
                )
                time.sleep(wait)

        raise ExtractionError(
            f"extraction failed after {self.config.max_retries} attempts: {last_error}"
        ) from last_error


# ---------------------------------------------------------------- the stage


def pending(store: Store, terms: Optional[list[str]] = None) -> list[dict[str, Any]]:
    """Sources with no catalog entry, or whose text has changed since the last extraction."""
    extracted = store.extracted_source_hashes()
    stale = []
    for source in store.iter_sources(terms):
        doc_code = source["doc_code"]
        current = source.get("text_sha256", "")
        if extracted.get(doc_code) != current:
            stale.append(source)
    return stale


def run(
    store: Store,
    config: ExtractConfig,
    run_id: str,
    terms: Optional[list[str]] = None,
) -> dict[str, Any]:
    work = pending(store, terms)

    budget_capped = False
    if config.max_docs_per_run is not None and len(work) > config.max_docs_per_run:
        # A prompt change, or a fresh catalog, makes every section in the term stale at once,
        # which is order of a thousand documents. Gemini 2.5 Pro with an 8k thinking budget is
        # the expensive part of this pipeline, so the cap is what stops a one-line edit from
        # turning into an unreviewed bill. The rest is picked up by subsequent runs; nothing
        # is lost, it just takes a few days.
        log.warning(
            "%d document(s) stale but MAX_EXTRACTIONS_PER_RUN=%d, deferring the rest",
            len(work),
            config.max_docs_per_run,
        )
        work = work[: config.max_docs_per_run]
        budget_capped = True

    log.info("extracting %d document(s) with %s", len(work), config.model)
    if not work:
        return {"extracted": 0, "failures": [], "budget_capped": False}

    extractor = GeminiExtractor(config)
    extracted = 0
    failures: list[dict[str, str]] = []

    for index, source in enumerate(work, start=1):
        doc_code = source["doc_code"]
        course_code = source.get("course_code")
        term = source.get("term")

        if not course_code or not term:
            failures.append(
                {"doc_code": doc_code, "error": "source has no course_code or term"}
            )
            continue

        try:
            assessments = extractor.extract(source["text"])
            store.upsert_extraction(
                doc_code=doc_code,
                course_code=course_code,
                term=term,
                assessments=assessments,
                source_sha=source.get("text_sha256", ""),
                model=config.model,
                temperature=config.temperature,
                run_id=run_id,
            )
            extracted += 1
            if index % 25 == 0 or index == len(work):
                log.info("  %d/%d extracted", index, len(work))
        except Exception as exc:  # noqa: BLE001 - recorded in the run report
            log.error("extraction failed for %s (%s %s): %s", doc_code, course_code, term, exc)
            failures.append(
                {
                    "doc_code": doc_code,
                    "course_code": course_code,
                    "term": term,
                    "error": f"{type(exc).__name__}: {exc}",
                }
            )

        time.sleep(config.request_delay_sec)

    return {
        "candidates": len(work),
        "extracted": extracted,
        "failures": failures,
        "budget_capped": budget_capped,
    }
