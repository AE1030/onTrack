"""Tests for the verify rules, including the two carried over from the Java test.

SyllabusAssessmentServiceIntegrationTest asserted these against the live collection and has
been @Disabled since it was written, because the Testcontainers Postgres it runs against starts
empty. Here they are pure functions over dictionaries, so they run on every build.
"""

import pytest

from syllabus_pipeline.verify import ERROR, WARNING, check_document


def document(*assessments, term="Winter 2026", course="BIOLOGY 3AA3", doc_code="abc123456"):
    return {
        "_id": {"course_code": course, "term": term, "doc_code": doc_code},
        "course_code": course,
        "term": term,
        "doc_code": doc_code,
        "assessments": {
            "grading_scheme": {
                "selection_rule": None,
                "schemes": {"scheme_1": {"label": None, "assessments": list(assessments)}},
            }
        },
    }


def assessment(**overrides):
    base = {
        "name": "Midterm",
        "category": "midterm",
        "due_date": "2026-02-23",
        "weight": 1.0,
        "bonus_assessment": False,
        "bonus_description": None,
        "replacement_rule": None,
        "occurrence": {"total": None},
    }
    base.update(overrides)
    return base


def rules(violations, severity=None):
    return {v.rule for v in violations if severity is None or v.severity == severity}


class TestCleanDocument:
    def test_a_well_formed_document_produces_nothing(self):
        assert check_document(document(assessment())) == []

    def test_weights_summing_to_one_across_several_assessments_is_clean(self):
        assert (
            check_document(
                document(
                    assessment(name="Midterm", weight=0.4),
                    assessment(name="Final", weight=0.6, category="final_exam"),
                )
            )
            == []
        )


class TestCarriedOverFromJava:
    def test_blank_name_is_an_error(self):
        # assertThat(dto.getAssessmentName()).isNotBlank()
        assert "name_blank" in rules(check_document(document(assessment(name="   "))), ERROR)

    def test_missing_weight_is_a_warning(self):
        # assertThat(dto.getWeights()).isNotNull().isNotEmpty() -- but weight is nullable by
        # design in the model, so an absent one is incomplete rather than corrupt.
        found = check_document(document(assessment(weight=None)))
        assert "weight_missing" in rules(found, WARNING)
        assert rules(found, ERROR) == set()

    def test_scheme_over_the_whole_course_is_an_error(self):
        # assertThat(totalWeight).isLessThanOrEqualTo(100.01), in fractions rather than percent
        found = check_document(
            document(assessment(name="A", weight=0.7), assessment(name="B", weight=0.5))
        )
        assert "scheme_weight_over_one" in rules(found, ERROR)

    def test_empty_scheme_is_an_error(self):
        assert "scheme_empty" in rules(check_document(document()), ERROR)

    def test_no_schemes_at_all_is_an_error(self):
        doc = document(assessment())
        doc["assessments"]["grading_scheme"]["schemes"] = {}
        assert "no_schemes" in rules(check_document(doc), ERROR)


class TestNewRules:
    def test_percentage_that_escaped_conversion_is_caught(self):
        # 15 where 0.15 was meant. Reads as a single assessment worth fifteen courses.
        assert "weight_above_one" in rules(check_document(document(assessment(weight=15))), ERROR)

    def test_bonus_carrying_weight_is_an_error(self):
        found = check_document(
            document(assessment(bonus_assessment=True, weight=0.05, bonus_description="Lab"))
        )
        assert "bonus_with_weight" in rules(found, ERROR)

    def test_bonus_without_a_description_is_an_error(self):
        found = check_document(document(assessment(bonus_assessment=True, weight=0)))
        assert "bonus_without_description" in rules(found, ERROR)

    def test_string_bonus_flag_is_an_error(self):
        # normalise() should make this unreachable for anything the pipeline writes. The rule
        # stays because verify also guards against documents written by hand.
        assert "bonus_flag_not_boolean" in rules(
            check_document(document(assessment(bonus_assessment="false"))), ERROR
        )

    def test_partial_replacement_rule_is_an_error(self):
        found = check_document(document(assessment(replacement_rule={"N": 1})))
        assert "replacement_rule_trigger_unknown" in rules(found, ERROR)

    def test_rule_dropping_every_occurrence_is_an_error(self):
        found = check_document(
            document(
                assessment(
                    replacement_rule={"N": 3, "trigger_type": "LOWEST_N_DROPPED"},
                    occurrence={"total": 3},
                )
            )
        )
        assert "replacement_rule_drops_everything" in rules(found, ERROR)

    def test_due_date_count_must_match_occurrence_total(self):
        found = check_document(
            document(
                assessment(
                    due_date=["2026-01-28", "2026-03-04"],
                    occurrence={"total": 3},
                )
            )
        )
        assert "due_date_count_mismatch" in rules(found, ERROR)

    def test_matching_due_date_count_is_clean(self):
        assert (
            check_document(
                document(
                    assessment(
                        due_date=["2026-01-28", "2026-03-04", "2026-03-25"],
                        occurrence={"total": 3},
                    )
                )
            )
            == []
        )

    @pytest.mark.parametrize("bad", ["Feb 23", "2026-2-23", "23-02-2026", ""])
    def test_non_iso_due_date_is_an_error(self, bad):
        assert "due_date_not_iso" in rules(check_document(document(assessment(due_date=bad))), ERROR)

    def test_tbd_is_accepted(self):
        assert check_document(document(assessment(due_date="TBD"))) == []

    def test_unknown_category_is_only_a_warning(self):
        found = check_document(document(assessment(category="vibes")))
        assert "category_unknown" in rules(found, WARNING)
        assert rules(found, ERROR) == set()

    def test_short_scheme_is_a_warning_not_an_error(self):
        # Real syllabi routinely leave part of the scheme implicit. Worth seeing, not worth
        # failing the nightly run over.
        found = check_document(document(assessment(weight=0.5)))
        assert "scheme_weight_not_one" in rules(found, WARNING)
        assert rules(found, ERROR) == set()


class TestViolationContext:
    def test_a_violation_carries_enough_to_find_the_document(self):
        found = check_document(document(assessment(name="")))
        violation = next(v for v in found if v.rule == "name_blank")
        assert violation.doc_code == "abc123456"
        assert violation.course_code == "BIOLOGY 3AA3"
        assert violation.term == "Winter 2026"
        assert violation.scheme == "scheme_1"
