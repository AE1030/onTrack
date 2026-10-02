package org.tracker.gpatracker.assessmenttable.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;
import org.tracker.gpatracker.assessmenttable.model.SchemeAssessment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The timing evidence the leaderboard rests on.
 *
 * <p>These three fields are the only part of a self-reported table the student does not control, so
 * every assertion here is really about one question: can a client make the server believe a grade
 * appeared earlier than it did?
 */
class GradeStampingTest {

    private static final Instant MONDAY = Instant.parse("2026-01-12T10:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-01-13T10:00:00Z");

    @Test
    @DisplayName("a grade arriving for the first time starts the clock")
    void firstGradeStartsTheClock() {
        List<AssessmentScheme> incoming = table(assessment("Midterm", "2026-01-10", new BigDecimal("80")));

        GradeStamper.stamp(incoming, null, MONDAY);

        SchemeAssessment stamped = first(incoming);
        assertThat(stamped.getFirstGradedAt()).isEqualTo(MONDAY);
        assertThat(stamped.getLastGradedAt()).isEqualTo(MONDAY);
        assertThat(stamped.getDueDateChangeCount()).isZero();
    }

    @Test
    @DisplayName("an ungraded row starts no clock at all")
    void ungradedRowIsNotStamped() {
        List<AssessmentScheme> incoming = table(assessment("Midterm", "2026-01-10", null));

        GradeStamper.stamp(incoming, null, MONDAY);

        assertThat(first(incoming).getFirstGradedAt()).isNull();
        assertThat(first(incoming).getLastGradedAt()).isNull();
    }

    /**
     * The load-bearing one. If correcting a mark reset firstGradedAt, every backfilled grade could
     * be laundered into its window by editing it a second time.
     */
    @Test
    @DisplayName("editing a grade moves lastGradedAt but never firstGradedAt")
    void editingAGradeCarriesTheFirstStampForward() {
        List<AssessmentScheme> previous = table(assessment("Midterm", "2026-01-10", new BigDecimal("80")));
        GradeStamper.stamp(previous, null, MONDAY);

        List<AssessmentScheme> incoming = table(assessment("Midterm", "2026-01-10", new BigDecimal("95")));
        GradeStamper.stamp(incoming, previous, TUESDAY);

        assertThat(first(incoming).getFirstGradedAt()).isEqualTo(MONDAY);
        assertThat(first(incoming).getLastGradedAt()).isEqualTo(TUESDAY);
    }

    @Test
    @DisplayName("re-saving an unchanged grade moves nothing")
    void unchangedGradeDoesNotMoveTheClock() {
        List<AssessmentScheme> previous = table(assessment("Midterm", "2026-01-10", new BigDecimal("80")));
        GradeStamper.stamp(previous, null, MONDAY);

        // Same mark, differently scaled. compareTo, not equals: 80 and 80.0 are one grade.
        List<AssessmentScheme> incoming = table(assessment("Midterm", "2026-01-10", new BigDecimal("80.0")));
        GradeStamper.stamp(incoming, previous, TUESDAY);

        assertThat(first(incoming).getLastGradedAt()).isEqualTo(MONDAY);
    }

    @Test
    @DisplayName("a timestamp supplied by the client is discarded")
    void clientSuppliedStampsAreOverwritten() {
        Instant lastYear = Instant.parse("2025-01-01T00:00:00Z");
        SchemeAssessment forged = assessment("Midterm", "2026-01-10", new BigDecimal("80"));
        forged.setFirstGradedAt(lastYear);
        forged.setLastGradedAt(lastYear);
        forged.setDueDateChangeCount(99);

        List<AssessmentScheme> incoming = table(forged);
        GradeStamper.stamp(incoming, null, MONDAY);

        assertThat(first(incoming).getFirstGradedAt()).isEqualTo(MONDAY);
        assertThat(first(incoming).getLastGradedAt()).isEqualTo(MONDAY);
        assertThat(first(incoming).getDueDateChangeCount()).isZero();
    }

    @Test
    @DisplayName("moving a due date after grading counts as churn")
    void dueDateMoveAfterGradingCounts() {
        List<AssessmentScheme> previous = table(assessment("Midterm", "2026-01-10", new BigDecimal("80")));
        GradeStamper.stamp(previous, null, MONDAY);

        List<AssessmentScheme> incoming = table(assessment("Midterm", "2026-02-20", new BigDecimal("80")));
        GradeStamper.stamp(incoming, previous, TUESDAY);

        assertThat(first(incoming).getDueDateChangeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("moving a due date before grading is housekeeping, not churn")
    void dueDateMoveBeforeGradingIsFree() {
        List<AssessmentScheme> previous = table(assessment("Midterm", "2026-01-10", null));
        GradeStamper.stamp(previous, null, MONDAY);

        List<AssessmentScheme> incoming = table(assessment("Midterm", "2026-02-20", null));
        GradeStamper.stamp(incoming, previous, TUESDAY);

        assertThat(first(incoming).getDueDateChangeCount()).isZero();
    }

    @Test
    @DisplayName("churn accumulates across saves rather than resetting")
    void churnAccumulates() {
        List<AssessmentScheme> first = table(assessment("Midterm", "2026-01-10", new BigDecimal("80")));
        GradeStamper.stamp(first, null, MONDAY);

        List<AssessmentScheme> second = table(assessment("Midterm", "2026-02-20", new BigDecimal("80")));
        GradeStamper.stamp(second, first, TUESDAY);

        List<AssessmentScheme> third = table(assessment("Midterm", "2026-03-01", new BigDecimal("80")));
        GradeStamper.stamp(third, second, TUESDAY);

        assertThat(GradeStampingTest.first(third).getDueDateChangeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a renamed row is a new row, and starts a new clock")
    void renamedRowIsNotMatched() {
        List<AssessmentScheme> previous = table(assessment("Midterm", "2026-01-10", new BigDecimal("80")));
        GradeStamper.stamp(previous, null, MONDAY);

        List<AssessmentScheme> incoming = table(assessment("Midterm 1", "2026-01-10", new BigDecimal("80")));
        GradeStamper.stamp(incoming, previous, TUESDAY);

        assertThat(first(incoming).getFirstGradedAt()).isEqualTo(TUESDAY);
    }

    // ------------------------------------------------------------------------- fixtures

    private static SchemeAssessment assessment(String name, String dueDate, BigDecimal grade) {
        SchemeAssessment assessment = new SchemeAssessment();
        assessment.setName(name);
        assessment.setDueDate(dueDate);
        assessment.setWeight(new BigDecimal("100"));
        assessment.setGrade(grade);
        return assessment;
    }

    private static List<AssessmentScheme> table(SchemeAssessment... assessments) {
        AssessmentScheme scheme = new AssessmentScheme();
        scheme.setSchemeName("Standard");
        scheme.setAssessments(List.of(assessments));
        return List.of(scheme);
    }

    private static SchemeAssessment first(List<AssessmentScheme> schemes) {
        return schemes.get(0).getAssessments().get(0);
    }
}
