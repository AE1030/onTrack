package org.tracker.gpatracker.leaderboard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.assessmenttable.model.SchemeAssessment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The two patterns the nightly job notices, and the damping they produce.
 *
 * <p>Every assertion here is bounded on both sides. A flag that fires too eagerly is worse than one
 * that never fires: this is a heuristic over ordinary student behaviour, and a course really can
 * have three things due in the same week.
 */
class BehaviorScoreTest {

    private static final double EPSILON = 1e-9;

    @Test
    @DisplayName("each flag costs a tenth, and the score floors at 0.5")
    void dampingIsLinearThenFloors() {
        assertThat(BehaviorScoring.behaviorScore(0)).isCloseTo(1.0, within(EPSILON));
        assertThat(BehaviorScoring.behaviorScore(1)).isCloseTo(0.9, within(EPSILON));
        assertThat(BehaviorScoring.behaviorScore(3)).isCloseTo(0.7, within(EPSILON));
        assertThat(BehaviorScoring.behaviorScore(5)).isCloseTo(0.5, within(EPSILON));
        assertThat(BehaviorScoring.behaviorScore(50)).isCloseTo(0.5, within(EPSILON));
    }

    @Test
    @DisplayName("churn fires above 30% of graded rows and not below")
    void churnThresholdIsBounded() {
        // 3 changes over 10 graded rows is exactly 30%, which is not above it.
        assertThat(BehaviorScoring.churnFlagged(List.of(course("C1", gradedRows(10, 3))))).isFalse();
        assertThat(BehaviorScoring.churnFlagged(List.of(course("C1", gradedRows(10, 4))))).isTrue();
    }

    @Test
    @DisplayName("a term with nothing graded cannot have rewritten history")
    void churnNeedsSomethingGraded() {
        List<SchemeAssessment> ungraded = new ArrayList<>();
        SchemeAssessment row = assessment("Lab", "2026-01-10", null);
        row.setDueDateChangeCount(9);
        ungraded.add(row);

        assertThat(BehaviorScoring.churnFlagged(List.of(course("C1", ungraded)))).isFalse();
    }

    @Test
    @DisplayName("clustering fires above 60% of a course's dated rows")
    void clusteringThresholdIsBounded() {
        // 3 of 5 is 60% exactly, so it does not fire; 4 of 5 does.
        assertThat(BehaviorScoring.clusteredCourseCount(
                List.of(course("C1", sharedDueDates(5, 3))))).isZero();
        assertThat(BehaviorScoring.clusteredCourseCount(
                List.of(course("C1", sharedDueDates(5, 4))))).isEqualTo(1);
    }

    @Test
    @DisplayName("a course with fewer than three dated rows is too small to cluster")
    void smallTablesCannotTrip() {
        // Two rows sharing one date is 100%, which would otherwise fire on every two-item scheme.
        assertThat(BehaviorScoring.clusteredCourseCount(
                List.of(course("C1", sharedDueDates(2, 2))))).isZero();
        assertThat(BehaviorScoring.clusteredCourseCount(
                List.of(course("C1", sharedDueDates(3, 3))))).isEqualTo(1);
    }

    @Test
    @DisplayName("clustering is counted per course, so several clustered courses cost more")
    void clusteringAccumulatesAcrossCourses() {
        assertThat(BehaviorScoring.clusteredCourseCount(List.of(
                course("C1", sharedDueDates(4, 4)),
                course("C2", sharedDueDates(4, 4)),
                course("C3", sharedDueDates(4, 1))))).isEqualTo(2);
    }

    @Test
    @DisplayName("an ordinary term trips nothing")
    void cleanTermIsUndamped() {
        assertThat(BehaviorScoring.flagCount(List.of(
                course("C1", gradedRows(6, 0)),
                course("C2", gradedRows(6, 1))))).isZero();
    }

    @Test
    @DisplayName("no combination of flags can void an entry")
    void dampingNeverReachesZero() {
        List<AssessmentTableDocument> worst = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            worst.add(course("C" + i, sharedDueDates(6, 6)));
        }
        assertThat(BehaviorScoring.behaviorScore(BehaviorScoring.flagCount(worst)))
                .isGreaterThanOrEqualTo(BehaviorScoring.DAMPING_FLOOR);
    }

    // ------------------------------------------------------------------------- fixtures

    /** {@code graded} rows on distinct dates, {@code changes} of which had their deadline moved. */
    private static List<SchemeAssessment> gradedRows(int graded, int changes) {
        List<SchemeAssessment> rows = new ArrayList<>();
        for (int i = 0; i < graded; i++) {
            SchemeAssessment row = assessment("A" + i, String.format("2026-01-%02d", i + 1),
                    new BigDecimal("75"));
            if (i < changes) {
                row.setDueDateChangeCount(1);
            }
            rows.add(row);
        }
        return rows;
    }

    /** {@code total} dated rows, {@code shared} of which land on one date. */
    private static List<SchemeAssessment> sharedDueDates(int total, int shared) {
        List<SchemeAssessment> rows = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String due = i < shared ? "2026-04-01" : String.format("2026-02-%02d", i + 1);
            rows.add(assessment("A" + i, due, new BigDecimal("75")));
        }
        return rows;
    }

    private static SchemeAssessment assessment(String name, String dueDate, BigDecimal grade) {
        SchemeAssessment assessment = new SchemeAssessment();
        assessment.setName(name);
        assessment.setDueDate(dueDate);
        assessment.setGrade(grade);
        return assessment;
    }

    private static AssessmentTableDocument course(String code, List<SchemeAssessment> assessments) {
        AssessmentScheme scheme = new AssessmentScheme();
        scheme.setSchemeName("Standard");
        scheme.setAssessments(assessments);

        AssessmentTableDocument table = new AssessmentTableDocument();
        table.setCourseCode(code);
        table.setTerm("Winter 2026");
        table.setSchemes(List.of(scheme));
        return table;
    }
}
