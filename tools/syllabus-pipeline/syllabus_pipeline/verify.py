"""Stage 3: check every catalog document and record what is wrong with it.

This is the Java integration test's job, moved to where the data is.

SyllabusAssessmentServiceIntegrationTest asserted two things over the real collection: that
each scheme produced a non-empty list of assessments with a name and a weight, and that a
scheme's weights did not exceed 100. It has been @Disabled since it was written, because it
reads a table that the Testcontainers Postgres in CI starts empty. So the checks existed
and never ran.

Running them here means they run every time the data changes, against the data that actually
changed, with no database credentials in CI and no second runtime in the schedule. The Java test
stays where it is, disabled, as the record of what the service itself should be asserted
against once there are fixtures to assert over.

The checks below are the two from the Java test plus the ones the extraction contract implies
but nothing was enforcing. Each is either an ERROR, meaning the document will mislead the
grade calculator, or a WARNING, meaning it is incomplete but safe.

Several of these are unreachable for documents this pipeline writes, because `normalise`
rejects or coerces the same faults at write time. They are kept deliberately: verify is the
check on what is *in* the collection, whatever put it there, and a rule that only ever passes
costs one comparison.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, asdict
from typing import Any, Iterable, Optional

from .store import Store

log = logging.getLogger(__name__)

ERROR = "error"
WARNING = "warning"

ISO_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

# Weights are stored as fractions of 1, not percentages. The Java test's 100.01 ceiling was
# against AssessmentTableDTO, which has already been scaled up by the service.
WEIGHT_CEILING = 1.0001
WEIGHT_SUM_TOLERANCE = 0.02

VALID_CATEGORIES = {
    "test", "midterm", "quiz", "lab", "lab_report", "lab_exam", "assignment", "homework",
    "project", "presentation", "essay", "report", "tutorial", "tutorial_quiz",
    "tutorial_assignment", "tutorial_participation", "participation", "attendance",
    "in_class_activity", "clicker", "discussion", "final_exam", "makeup_exam",
    "deferred_exam", "oral_exam", "practical_exam", "practicum", "fieldwork", "simulation",
    "portfolio", "case_study", "bonus", "mandatory_requirement", "pass_fail_component",
    "threshold_requirement",
}


@dataclass
class Violation:
    doc_code: str
    course_code: Optional[str]
    term: Optional[str]
    scheme: Optional[str]
    assessment: Optional[str]
    rule: str
    severity: str
    detail: str

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def _check_assessment(
    item: Any,
    context: dict[str, Any],
    scheme_id: str,
    out: list[Violation],
) -> Optional[float]:
    """Check one assessment. Returns its weight for the scheme total, or None."""

    def add(rule: str, severity: str, detail: str, name: Optional[str] = None) -> None:
        out.append(
            Violation(
                doc_code=context["doc_code"],
                course_code=context.get("course_code"),
                term=context.get("term"),
                scheme=scheme_id,
                assessment=name if name is not None else (item.get("name") if isinstance(item, dict) else None),
                rule=rule,
                severity=severity,
                detail=detail,
            )
        )

    if not isinstance(item, dict):
        add("assessment_not_an_object", ERROR, f"got {type(item).__name__}", name=None)
        return None

    # --- from the Java test: every assessment needs a name ---
    name = item.get("name")
    if not isinstance(name, str) or not name.strip():
        add("name_blank", ERROR, "assessment has no usable name")

    category = item.get("category")
    if category is None:
        add("category_missing", WARNING, "no category, the calendar cannot classify it")
    elif category not in VALID_CATEGORIES:
        add("category_unknown", WARNING, f"category {category!r} is not in the agreed set")

    # --- from the Java test: every assessment needs a weight ---
    weight = item.get("weight")
    if weight is None:
        add("weight_missing", WARNING, "no weight, contributes nothing to a projection")
    elif not isinstance(weight, (int, float)) or isinstance(weight, bool):
        add("weight_not_numeric", ERROR, f"weight is {type(weight).__name__}: {weight!r}")
        weight = None
    elif weight < 0:
        add("weight_negative", ERROR, f"weight is {weight}")
    elif weight > WEIGHT_CEILING:
        # A weight over 1 is almost always a percentage that escaped conversion: 15 for 0.15.
        add("weight_above_one", ERROR, f"weight is {weight}, expected a fraction of 1")

    # --- the bonus contract ---
    bonus = item.get("bonus_assessment")
    if not isinstance(bonus, bool):
        add("bonus_flag_not_boolean", ERROR, f"bonus_assessment is {bonus!r}")
        bonus = str(bonus).strip().lower() == "true"

    bonus_description = item.get("bonus_description")
    if bonus:
        if weight not in (0, 0.0, None) :
            add("bonus_with_weight", ERROR, f"bonus assessment carries weight {weight}")
        if not bonus_description:
            add("bonus_without_description", ERROR, "bonus_assessment is true but bonus_description is null")
    elif bonus_description:
        add("description_without_bonus", WARNING, "bonus_description set but bonus_assessment is false")

    # --- the replacement rule contract ---
    rule = item.get("replacement_rule")
    if rule is not None:
        if not isinstance(rule, dict):
            add("replacement_rule_not_an_object", ERROR, f"got {type(rule).__name__}")
        else:
            n = rule.get("N")
            trigger = rule.get("trigger_type")
            if not isinstance(n, int) or isinstance(n, bool):
                add("replacement_rule_n_not_int", ERROR, f"N is {n!r}")
            elif n < 0:
                add("replacement_rule_n_negative", ERROR, f"N is {n}")
            if trigger != "LOWEST_N_DROPPED":
                add("replacement_rule_trigger_unknown", ERROR, f"trigger_type is {trigger!r}")

    # --- occurrence and due dates ---
    occurrence = item.get("occurrence")
    total = occurrence.get("total") if isinstance(occurrence, dict) else None
    if total is not None and (not isinstance(total, int) or isinstance(total, bool)):
        add("occurrence_total_not_int", ERROR, f"occurrence.total is {total!r}")
        total = None
    elif isinstance(total, int) and total < 1:
        add("occurrence_total_not_positive", ERROR, f"occurrence.total is {total}")

    if isinstance(rule, dict) and isinstance(rule.get("N"), int) and isinstance(total, int):
        # Dropping as many assessments as exist, or more, leaves nothing to grade.
        if rule["N"] >= total:
            add(
                "replacement_rule_drops_everything",
                ERROR,
                f"N={rule['N']} against occurrence.total={total}",
            )

    due_date = item.get("due_date")
    if isinstance(due_date, list):
        if not due_date:
            add("due_date_empty_list", ERROR, "due_date is an empty list, expected null")
        for entry in due_date:
            if not isinstance(entry, str) or not ISO_DATE_RE.match(entry):
                add("due_date_not_iso", ERROR, f"due_date entry {entry!r} is not YYYY-MM-DD")
        if isinstance(total, int) and len(due_date) != total:
            # The prompt asks for one date per occurrence, index-aligned. A mismatch means the
            # calendar would put deadlines against the wrong instances.
            add(
                "due_date_count_mismatch",
                ERROR,
                f"{len(due_date)} date(s) against occurrence.total={total}",
            )
    elif isinstance(due_date, str):
        if due_date != "TBD" and not ISO_DATE_RE.match(due_date):
            add("due_date_not_iso", ERROR, f"due_date {due_date!r} is not YYYY-MM-DD or TBD")
    elif due_date is not None:
        add("due_date_wrong_type", ERROR, f"due_date is {type(due_date).__name__}")

    return float(weight) if isinstance(weight, (int, float)) and not isinstance(weight, bool) else None


def check_document(doc: dict[str, Any]) -> list[Violation]:
    out: list[Violation] = []
    # The store yields flat columns, so doc_code is normally top level. The nested lookup is kept
    # for the unit-test fixtures, which build documents in the old Mongo shape with a composite
    # _id -- harmless to support, and it keeps those tests exercising the real rule.
    doc_id = doc.get("_id") or {}
    context = {
        "doc_code": doc_id.get("doc_code") or doc.get("doc_code") or "<unknown>",
        "course_code": doc.get("course_code"),
        "term": doc.get("term"),
    }

    def add(rule: str, severity: str, detail: str, scheme: Optional[str] = None) -> None:
        out.append(
            Violation(
                doc_code=context["doc_code"],
                course_code=context.get("course_code"),
                term=context.get("term"),
                scheme=scheme,
                assessment=None,
                rule=rule,
                severity=severity,
                detail=detail,
            )
        )

    schemes = (
        (doc.get("assessments") or {}).get("grading_scheme") or {}
    ).get("schemes")

    if not isinstance(schemes, dict) or not schemes:
        # The Java test's first assertion. `normalise` rejects this at write time, so it
        # should be unreachable for anything the pipeline stores; it stays because verify
        # checks what is in the collection whatever put it there.
        add("no_schemes", ERROR, "document has no grading scheme")
        return out

    for scheme_id, definition in schemes.items():
        if not isinstance(definition, dict):
            add("scheme_not_an_object", ERROR, f"got {type(definition).__name__}", scheme_id)
            continue

        items = definition.get("assessments")
        if not isinstance(items, list) or not items:
            add("scheme_empty", ERROR, "scheme has no assessments", scheme_id)
            continue

        total = 0.0
        counted = 0
        for item in items:
            weight = _check_assessment(item, context, scheme_id, out)
            if weight is not None:
                total += weight
                counted += 1

        # --- from the Java test: a scheme's weights must not exceed the whole ---
        if total > WEIGHT_CEILING:
            add(
                "scheme_weight_over_one",
                ERROR,
                f"weights total {total:.4f}, which is more than the whole course",
                scheme_id,
            )
        elif counted and abs(total - 1.0) > WEIGHT_SUM_TOLERANCE:
            # The prompt says a scheme must sum to exactly 1.0. It frequently does not, and a
            # short scheme is usually a real syllabus that left something implicit rather than
            # a broken extraction, so this is worth seeing without failing the run.
            add(
                "scheme_weight_not_one",
                WARNING,
                f"weights total {total:.4f}, expected 1.0",
                scheme_id,
            )

    return out


def run(store: Store, terms: Optional[list[str]] = None) -> dict[str, Any]:
    violations: list[Violation] = []
    checked = 0

    for doc in store.iter_catalog(terms):
        checked += 1
        violations.extend(check_document(doc))

    errors = [v for v in violations if v.severity == ERROR]
    warnings = [v for v in violations if v.severity == WARNING]

    by_rule: dict[str, int] = {}
    for violation in violations:
        by_rule[violation.rule] = by_rule.get(violation.rule, 0) + 1

    flagged = {v.doc_code for v in errors}

    log.info(
        "verified %d document(s): %d error(s) across %d document(s), %d warning(s)",
        checked,
        len(errors),
        len(flagged),
        len(warnings),
    )
    for rule, count in sorted(by_rule.items(), key=lambda kv: -kv[1]):
        log.info("  %-34s %d", rule, count)

    return {
        "checked": checked,
        "errors": len(errors),
        "warnings": len(warnings),
        "documents_flagged": len(flagged),
        "by_rule": by_rule,
        # Capped so one systemic fault cannot write a 16MB run document. The counts above stay
        # exact; only the examples are trimmed.
        "violations": [v.as_dict() for v in errors[:500]],
        "warning_samples": [v.as_dict() for v in warnings[:100]],
    }


def format_summary(result: dict[str, Any]) -> Iterable[str]:
    yield f"checked {result['checked']} document(s)"
    yield f"{result['errors']} error(s) across {result['documents_flagged']} document(s)"
    yield f"{result['warnings']} warning(s)"
