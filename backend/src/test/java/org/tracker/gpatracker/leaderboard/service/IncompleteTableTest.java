package org.tracker.gpatracker.leaderboard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;
import org.tracker.gpatracker.assessmenttable.model.SchemeAssessment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The two gates in front of a course percentage: is this scheme a whole course, and did the server
 * actually watch each grade appear.
 *
 * <p>Half a table is not a claim. A course missing any non-bonus grade earns no points at all
 * rather than a partial score, so stopping mid-term costs the student nothing and gains them
 * nothing.
 */
class IncompleteTableTest {

    private static final Instant NOW = Instant.parse("2026-04-01T00:00:00Z");

    @Test
    @DisplayName("a complete, well-formed table scores")
    void completeTableScores() {
        AssessmentScheme scheme = scheme(
                graded("Midterm", "2026-02-01", 40, 80, "2026-02-02T12:00:00Z"),
                graded("Final", "2026-03-01", 60, 90, "2026-03-02T12:00:00Z"));

        assertThat(CourseScoring.percentage(List.of(scheme), NOW))
                .hasValueSatisfying(percentage ->
                        assertThat(percentage.doubleValue()).isCloseTo(86.0, within(0.001)));
    }

    @Test
    @DisplayName("weights are read as fractions or percentages, whichever the table uses")
    void bothWeightConventionsAreAccepted() {
        // The client normalises what the user types into fractions summing to 1.0; extracted
        // syllabus weights arrive as percentages summing to 100. Both are whole courses.
        AssessmentScheme fractions = scheme(
                gradedFraction("Midterm", "2026-02-01", "0.4", 80, "2026-02-02T12:00:00Z"),
                gradedFraction("Final", "2026-03-01", "0.6", 90, "2026-03-02T12:00:00Z"));

        assertThat(CourseScoring.percentage(List.of(fractions), NOW))
                .hasValueSatisfying(percentage ->
                        assertThat(percentage.doubleValue()).isCloseTo(86.0, within(0.001)));
    }

    @Test
    @DisplayName("weights that are not a whole course produce no percentage")
    void malformedWeightsAreIneligible() {
        // 80% of a course is a meaningless percentage, not a suspicious one. Nothing here is
        // judging the student -- the arithmetic simply has no answer.
        AssessmentScheme scheme = scheme(
                graded("Midterm", "2026-02-01", 40, 80, "2026-02-02T12:00:00Z"),
                graded("Final", "2026-03-01", 40, 90, "2026-03-02T12:00:00Z"));

        assertThat(CourseScoring.percentage(List.of(scheme), NOW)).isEmpty();
    }

    @Test
    @DisplayName("a missing non-bonus grade drops the whole course")
    void missingGradeDropsTheCourse() {
        AssessmentScheme scheme = scheme(
                graded("Midterm", "2026-02-01", 40, 80, "2026-02-02T12:00:00Z"),
                ungraded("Final", "2026-03-01", 60));

        assertThat(CourseScoring.percentage(List.of(scheme), NOW)).isEmpty();
    }

    @Test
    @DisplayName("a blank bonus assessment does not make a table incomplete")
    void blankBonusIsFine() {
        AssessmentScheme scheme = scheme(
                graded("Midterm", "2026-02-01", 40, 80, "2026-02-02T12:00:00Z"),
                graded("Final", "2026-03-01", 60, 90, "2026-03-02T12:00:00Z"),
                ungraded("Bonus quiz", "2026-03-10", 0));

        assertThat(CourseScoring.percentage(List.of(scheme), NOW)).isPresent();
    }

    @Test
    @DisplayName("a grade entered before its due date does not count")
    void gradeBeforeTheWindowIsRejected() {
        AssessmentScheme scheme = scheme(
                graded("Midterm", "2026-02-01", 100, 95, "2026-01-15T12:00:00Z"));

        assertThat(CourseScoring.percentage(List.of(scheme), NOW)).isEmpty();
    }

    @Test
    @DisplayName("a grade backfilled more than fourteen days late does not count")
    void gradeAfterTheWindowIsRejected() {
        AssessmentScheme onTime = scheme(
                graded("Midterm", "2026-02-01", 100, 95, "2026-02-15T00:00:00Z"));
        AssessmentScheme late = scheme(
                graded("Midterm", "2026-02-01", 100, 95, "2026-02-16T00:00:00Z"));

        assertThat(CourseScoring.percentage(List.of(onTime), NOW)).isPresent();
        assertThat(CourseScoring.percentage(List.of(late), NOW)).isEmpty();
    }

    @Test
    @DisplayName("backfilling a whole term at once earns nothing")
    void endOfTermBackfillEarnsNothing() {
        Instant lastDay = Instant.parse("2026-04-20T12:00:00Z");
        AssessmentScheme scheme = scheme(
                graded("Midterm", "2026-02-01", 40, 100, lastDay.toString()),
                graded("Final", "2026-03-01", 60, 100, lastDay.toString()));

        assertThat(CourseScoring.percentage(List.of(scheme), lastDay)).isEmpty();
    }

    /**
     * The hole this closes: without a due date there is no window to check against, so accepting
     * an undated row would make clearing the date the escape hatch for the whole timing defence.
     */
    @Test
    @DisplayName("a row with no parseable due date cannot be time-locked, so it does not count")
    void undatedRowIsRejected() {
        AssessmentScheme scheme = scheme(
                graded("Midterm", null, 100, 95, "2026-02-02T12:00:00Z"));

        assertThat(CourseScoring.percentage(List.of(scheme), NOW)).isEmpty();
    }

    @Test
    @DisplayName("a recurring row's due date resolves to its first occurrence")
    void recurringDueDateUsesTheFirstOccurrence() {
        AssessmentScheme scheme = scheme(
                graded("Weekly quiz", "2026-02-01, 2026-02-08, 2026-02-15", 100, 88,
                        "2026-02-03T12:00:00Z"));

        assertThat(CourseScoring.percentage(List.of(scheme), NOW)).isPresent();
    }

    @Test
    @DisplayName("a course with alternative schemes takes the better of them")
    void alternativeSchemesTakeTheBest() {
        AssessmentScheme withFinal = scheme(
                graded("Midterm", "2026-02-01", 50, 70, "2026-02-02T12:00:00Z"),
                graded("Final", "2026-03-01", 50, 70, "2026-03-02T12:00:00Z"));
        AssessmentScheme finalWeighted = scheme(
                graded("Midterm", "2026-02-01", 20, 70, "2026-02-02T12:00:00Z"),
                graded("Final", "2026-03-01", 80, 90, "2026-03-02T12:00:00Z"));

        assertThat(CourseScoring.percentage(List.of(withFinal, finalWeighted), NOW))
                .hasValueSatisfying(percentage ->
                        assertThat(percentage.doubleValue()).isCloseTo(86.0, within(0.001)));
    }

    @Test
    @DisplayName("one unusable scheme does not sink a course that has a usable one")
    void oneBadSchemeDoesNotSinkTheCourse() {
        AssessmentScheme incomplete = scheme(
                graded("Midterm", "2026-02-01", 50, 70, "2026-02-02T12:00:00Z"),
                ungraded("Final", "2026-03-01", 50));
        AssessmentScheme complete = scheme(
                graded("Midterm", "2026-02-01", 100, 70, "2026-02-02T12:00:00Z"));

        assertThat(CourseScoring.percentage(List.of(incomplete, complete), NOW)).isPresent();
    }

    // ------------------------------------------------------------------------- fixtures

    private static SchemeAssessment graded(String name, String due, int weight, int grade, String at) {
        SchemeAssessment assessment = ungraded(name, due, weight);
        assessment.setGrade(new BigDecimal(grade));
        assessment.setFirstGradedAt(Instant.parse(at));
        assessment.setLastGradedAt(Instant.parse(at));
        return assessment;
    }

    private static SchemeAssessment gradedFraction(String name, String due, String weight,
                                                   int grade, String at) {
        SchemeAssessment assessment = new SchemeAssessment();
        assessment.setName(name);
        assessment.setDueDate(due);
        assessment.setWeight(new BigDecimal(weight));
        assessment.setGrade(new BigDecimal(grade));
        assessment.setFirstGradedAt(Instant.parse(at));
        return assessment;
    }

    private static SchemeAssessment ungraded(String name, String due, int weight) {
        SchemeAssessment assessment = new SchemeAssessment();
        assessment.setName(name);
        assessment.setDueDate(due);
        assessment.setWeight(new BigDecimal(weight));
        return assessment;
    }

    private static AssessmentScheme scheme(SchemeAssessment... assessments) {
        AssessmentScheme scheme = new AssessmentScheme();
        scheme.setSchemeName("Standard");
        scheme.setAssessments(new ArrayList<>(List.of(assessments)));
        return scheme;
    }
}
