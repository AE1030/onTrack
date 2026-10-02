package org.tracker.gpatracker.leaderboard.service;

import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.assessmenttable.model.SchemeAssessment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two patterns worth noticing in a term's tables, and the damping they produce.
 *
 * <p>This is not a fraud detector and must not be described as one. Both flags have innocent
 * explanations — a course really can reschedule, and a course really can have three things due the
 * same week — so nothing here voids an entry or accuses anyone. All a flag does is scale the score
 * down slightly, with a floor, so that a table exhibiting several of these patterns at once ranks a
 * little below one that does not.
 *
 * <p>The flags themselves are computed from the server-set fields on {@code SchemeAssessment}, so
 * neither can be avoided by editing the table afterwards.
 */
public final class BehaviorScoring {

    /** Above this share of graded rows having had their deadline moved after grading, churn fires. */
    public static final double CHURN_THRESHOLD = 0.30;

    /** Above this share of a course's rows sharing one due date, clustering fires. */
    public static final double CLUSTER_THRESHOLD = 0.60;

    /** Below this many assessments a course is too small for clustering to mean anything. */
    public static final int CLUSTER_MIN_ASSESSMENTS = 3;

    /** How much each flag costs. */
    public static final double DAMPING_PER_FLAG = 0.1;

    /** However many flags trip, the multiplier never falls below this. */
    public static final double DAMPING_FLOOR = 0.5;

    private BehaviorScoring() {
    }

    /**
     * The multiplier applied to every mode's score.
     *
     * <p>The floor is what keeps this a damping rather than a punishment: a student who trips every
     * flag in every course still keeps half their score, because the flags are heuristics over
     * ordinary behaviour and being wrong about one of them must not cost someone their season.
     */
    public static double behaviorScore(int flagCount) {
        return Math.max(DAMPING_FLOOR, 1.0 - DAMPING_PER_FLAG * Math.max(0, flagCount));
    }

    /**
     * Counts the flags across one student's tables for a term.
     *
     * <p>Churn is measured once across the whole term rather than per course — one course
     * rescheduling twice is not a pattern, whereas a term where a third of all graded rows moved
     * after grading is. Clustering is per course, because a single course with everything due on
     * one day is exactly the shape being looked for, and a student with several such courses has
     * more of it than a student with one.
     */
    public static int flagCount(List<AssessmentTableDocument> tables) {
        if (tables == null || tables.isEmpty()) {
            return 0;
        }
        return (churnFlagged(tables) ? 1 : 0) + clusteredCourseCount(tables);
    }

    /**
     * Deadlines moved after the mark was already in.
     *
     * <p>Moving a due date <em>before</em> grading it is housekeeping — a correction to a date the
     * syllabus parser got wrong, or a real reschedule — and {@code GradeStamper} does not count it.
     * Only a move that rewrites the window a grade was already judged against lands here.
     */
    static boolean churnFlagged(List<AssessmentTableDocument> tables) {
        int graded = 0;
        int changes = 0;
        for (SchemeAssessment assessment : allAssessments(tables)) {
            if (assessment.getGrade() != null) {
                graded++;
            }
            changes += assessment.getDueDateChangeCount();
        }
        if (graded == 0) {
            // Nothing graded means nothing was rewritten after the fact, whatever the counters say.
            return false;
        }
        return (double) changes / graded > CHURN_THRESHOLD;
    }

    /** How many of this student's courses have one due date covering most of a scheme. */
    static int clusteredCourseCount(List<AssessmentTableDocument> tables) {
        int flagged = 0;
        for (AssessmentTableDocument table : tables) {
            if (table != null && table.getSchemes() != null && isClustered(table)) {
                flagged++;
            }
        }
        return flagged;
    }

    private static boolean isClustered(AssessmentTableDocument table) {
        for (AssessmentScheme scheme : table.getSchemes()) {
            if (scheme == null || scheme.getAssessments() == null) {
                continue;
            }
            // Counted per scheme rather than per course: a course with two alternative schemes
            // holds each assessment twice, and pooling them would dilute a genuine cluster.
            Map<String, Integer> byDueDate = new HashMap<>();
            int dated = 0;
            for (SchemeAssessment assessment : scheme.getAssessments()) {
                if (assessment == null || assessment.getDueDate() == null || assessment.getDueDate().isBlank()) {
                    continue;
                }
                dated++;
                byDueDate.merge(assessment.getDueDate(), 1, Integer::sum);
            }
            // The minimum exists so a two-item scheme cannot trip this by simply being small,
            // where one shared date is already 50% and two is 100%.
            if (dated < CLUSTER_MIN_ASSESSMENTS) {
                continue;
            }
            for (int count : byDueDate.values()) {
                if ((double) count / dated > CLUSTER_THRESHOLD) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<SchemeAssessment> allAssessments(List<AssessmentTableDocument> tables) {
        List<SchemeAssessment> all = new ArrayList<>();
        for (AssessmentTableDocument table : tables) {
            if (table == null || table.getSchemes() == null) {
                continue;
            }
            for (AssessmentScheme scheme : table.getSchemes()) {
                if (scheme == null || scheme.getAssessments() == null) {
                    continue;
                }
                for (SchemeAssessment assessment : scheme.getAssessments()) {
                    if (assessment != null) {
                        all.add(assessment);
                    }
                }
            }
        }
        return all;
    }
}
