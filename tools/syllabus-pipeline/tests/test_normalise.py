"""Tests for extract.normalise, the function that enforces the storage contract.

Every case here is a shape the model has actually produced when asked for this schema, not an
invented one. Asked the same question a few hundred times it answers with a boolean most of
the time and the string "false" the rest, and the same goes for every numeric field, so the
storage layer cannot assume the prompt was obeyed.
"""

import pytest

from syllabus_pipeline.extract import ExtractionError, normalise


def scheme(*assessments):
    """The minimum wrapper normalise expects around a list of assessments."""
    return {
        "grading_scheme": {
            "selection_rule": "MAX",
            "schemes": {"scheme_1": {"label": None, "assessments": list(assessments)}},
        }
    }


def only_assessment(result):
    return result["grading_scheme"]["schemes"]["scheme_1"]["assessments"][0]


def assessment(**overrides):
    base = {
        "name": "Midterm",
        "category": "midterm",
        "due_date": "2026-02-23",
        "weight": 0.2,
        "start_time": None,
        "end_time": None,
        "location": None,
        "description": None,
        "bonus_assessment": False,
        "bonus_description": None,
        "replacement_rule": None,
        "occurrence": {"total": None},
    }
    base.update(overrides)
    return base


class TestTypeCoercion:
    @pytest.mark.parametrize(
        "raw,expected",
        [("true", True), ("false", False), ("True", True), (True, True), (False, False)],
    )
    def test_bonus_flag_becomes_a_real_boolean(self, raw, expected):
        # Java declares Boolean here, so a quoted "false" would land as a string in the jsonb and
        # be absorbed by Spring's conversion service rather than caught.
        result = normalise(scheme(assessment(bonus_assessment=raw, weight=0, bonus_description="x")))
        assert only_assessment(result)["bonus_assessment"] is expected

    def test_replacement_rule_n_becomes_an_int(self):
        result = normalise(
            scheme(
                assessment(
                    replacement_rule={"N": "2", "trigger_type": "LOWEST_N_DROPPED"},
                    occurrence={"total": 4},
                )
            )
        )
        assert only_assessment(result)["replacement_rule"] == {
            "N": 2,
            "trigger_type": "LOWEST_N_DROPPED",
        }

    def test_occurrence_total_becomes_an_int(self):
        result = normalise(scheme(assessment(occurrence={"total": "6"})))
        assert only_assessment(result)["occurrence"]["total"] == 6

    def test_missing_occurrence_becomes_an_explicit_null(self):
        # The model routinely omits the key entirely. Java reads Integer, so absent and null
        # look the same there, but one shape in the collection keeps the verify rules simple.
        result = normalise(scheme(assessment(occurrence=None)))
        assert only_assessment(result)["occurrence"] == {"total": None}

    def test_percentage_weight_string_is_parsed(self):
        result = normalise(scheme(assessment(weight="0.25")))
        assert only_assessment(result)["weight"] == 0.25


class TestBonusContract:
    def test_bonus_weight_is_forced_to_zero(self):
        # The prompt says so; this makes it true. A bonus with real weight inflates every
        # projection for the course.
        result = normalise(
            scheme(assessment(bonus_assessment=True, weight=0.05, bonus_description="Extra lab"))
        )
        assert only_assessment(result)["weight"] == 0.0

    def test_description_without_the_flag_is_dropped(self):
        result = normalise(scheme(assessment(bonus_assessment=False, bonus_description="Extra lab")))
        assert only_assessment(result)["bonus_description"] is None


class TestReplacementRule:
    def test_partial_rule_is_dropped_entirely(self):
        # A rule missing its trigger would otherwise drop a different number of assessments
        # than the syllabus states.
        result = normalise(scheme(assessment(replacement_rule={"N": 1})))
        assert only_assessment(result)["replacement_rule"] is None

    def test_unparseable_n_drops_the_rule(self):
        result = normalise(
            scheme(assessment(replacement_rule={"N": "some", "trigger_type": "LOWEST_N_DROPPED"}))
        )
        assert only_assessment(result)["replacement_rule"] is None

    def test_unknown_trigger_drops_the_rule(self):
        result = normalise(
            scheme(assessment(replacement_rule={"N": 1, "trigger_type": "BEST_OF"}))
        )
        assert only_assessment(result)["replacement_rule"] is None


class TestDueDates:
    def test_list_of_dates_is_kept(self):
        dates = ["2026-01-28", "2026-03-04", "2026-03-25"]
        result = normalise(scheme(assessment(due_date=dates, occurrence={"total": 3})))
        assert only_assessment(result)["due_date"] == dates

    def test_single_element_list_collapses_to_a_string(self):
        # Java maps due_date onto one String. A list gets there through Spring's
        # collection-to-String converter, which joins on "," with no space. Collapsing the
        # common case means it never takes that road.
        result = normalise(scheme(assessment(due_date=["2026-01-28"])))
        assert only_assessment(result)["due_date"] == "2026-01-28"

    def test_empty_list_becomes_null(self):
        result = normalise(scheme(assessment(due_date=[])))
        assert only_assessment(result)["due_date"] is None

    def test_tbd_survives(self):
        result = normalise(scheme(assessment(due_date="TBD")))
        assert only_assessment(result)["due_date"] == "TBD"


class TestRejection:
    def test_no_grading_scheme_raises(self):
        with pytest.raises(ExtractionError, match="grading_scheme"):
            normalise({"something_else": {}})

    def test_empty_schemes_map_raises(self):
        # The model answers this way when a syllabus states no grading table at all. Storing
        # it would put a course in the catalog that resolves to nothing usable.
        with pytest.raises(ExtractionError, match="missing or empty"):
            normalise({"grading_scheme": {"selection_rule": "MAX", "schemes": {}}})

    def test_scheme_of_nameless_assessments_raises(self):
        with pytest.raises(ExtractionError, match="empty after normalisation"):
            normalise(scheme(assessment(name=""), assessment(name=None)))

    def test_non_object_raises(self):
        with pytest.raises(ExtractionError):
            normalise(["not", "an", "object"])


class TestSelectionRule:
    def test_an_explicit_max_is_left_alone(self):
        result = normalise(scheme(assessment()))
        assert result["grading_scheme"]["selection_rule"] == "MAX"

    def test_unknown_rule_with_one_scheme_becomes_null(self):
        # With nothing to select between, no rule is the honest answer, and null is what
        # Java's nullable enum field reads cleanly.
        payload = {
            "grading_scheme": {
                "selection_rule": "SUM",
                "schemes": {"scheme_1": {"label": None, "assessments": [assessment()]}},
            }
        }
        assert normalise(payload)["grading_scheme"]["selection_rule"] is None

    def test_unknown_rule_with_several_schemes_falls_back_to_max(self):
        # Java maps this onto an enum whose only constant is MAX. Anything else fails to
        # deserialize the whole document, taking out a course's syllabus entirely.
        payload = {
            "grading_scheme": {
                "selection_rule": "MINIMUM",
                "schemes": {
                    "a": {"label": "A", "assessments": [assessment()]},
                    "b": {"label": "B", "assessments": [assessment(name="Final")]},
                },
            }
        }
        assert normalise(payload)["grading_scheme"]["selection_rule"] == "MAX"
