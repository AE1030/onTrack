package org.tracker.gpatracker.leaderboard.service;

import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;
import org.tracker.gpatracker.assessmenttable.model.SchemeAssessment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one course's self-reported assessment table into a percentage, or into nothing.
 *
 * <p>Two gates stand in front of that number, and both are structural rather than judgemental.
 *
 * <p><b>Well-formedness.</b> A scheme only scores if its weights actually sum to a whole course.
 * This replaces what was going to be a trust check, and the difference matters: a scheme whose
 * weights sum to 80 produces a meaningless percentage, whereas a scheme with unusual weights is not
 * evidence of anything. There is nothing here that punishes a student for a course being odd.
 *
 * <p><b>Timing.</b> A grade only counts if the server saw it appear between the assessment's due
 * date and fourteen days after. That is the one thing on a self-reported table the student does not
 * control, and it is what makes backfilling a whole term on the last day worth nothing.
 *
 * <p>Pure and static — the whole class is a function of a table and a clock.
 */
public final class CourseScoring {

    /** How long after the due date a grade may still be entered and counted. */
    public static final Duration ENTRY_WINDOW = Duration.ofDays(14);

    /** How far a scheme's weights may drift from a whole course, in percentage points. */
    private static final double WEIGHT_TOLERANCE = 0.5;

    /** Weights arrive as fractions from the client and as percentages from some syllabi. */
    private static final double FRACTION_TOLERANCE = 0.005;

    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    private CourseScoring() {
    }

    /**
     * The course percentage this table works out to, or empty if it does not score.
     *
     * <p>A course with more than one grading scheme takes the best of them, which is what an
     * alternative scheme in a syllabus is for — the student is graded under whichever treats them
     * better. A scheme that fails either gate simply is not a candidate; only if none of them
     * qualifies does the course drop out entirely.
     */
    public static Optional<BigDecimal> percentage(List<AssessmentScheme> schemes, Instant now) {
        if (schemes == null || schemes.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal best = null;
        for (AssessmentScheme scheme : schemes) {
            Optional<BigDecimal> candidate = schemePercentage(scheme, now);
            if (candidate.isPresent() && (best == null || candidate.get().compareTo(best) > 0)) {
                best = candidate.get();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * One scheme's percentage, or empty if its weights are malformed or a required grade is
     * missing or out of its window.
     *
     * <p>Half a table is not a claim: a course missing any non-bonus grade earns no points at all
     * rather than a partial score. Stopping mid-term costs the student nothing and gains them
     * nothing, which is the correct treatment of an incomplete report.
     */
    static Optional<BigDecimal> schemePercentage(AssessmentScheme scheme, Instant now) {
        if (scheme == null || scheme.getAssessments() == null || scheme.getAssessments().isEmpty()) {
            return Optional.empty();
        }
        List<SchemeAssessment> assessments = scheme.getAssessments();

        Optional<Double> scale = weightScale(assessments);
        if (scale.isEmpty()) {
            return Optional.empty();
        }

        double total = 0.0;
        for (SchemeAssessment assessment : assessments) {
            if (assessment == null) {
                continue;
            }
            double weight = weightOf(assessment) * scale.get();

            // Zero-weight rows are the bonus assessments: optional by definition, and the extractor
            // is instructed to give them a weight of 0. They may be blank without making the table
            // incomplete, and they contribute nothing when they are filled in.
            if (weight <= 0.0) {
                continue;
            }
            if (!counts(assessment, now)) {
                return Optional.empty();
            }
            total += weight / 100.0 * assessment.getGrade().doubleValue();
        }
        return Optional.of(BigDecimal.valueOf(total));
    }

    /**
     * Whether one graded row is admissible: it has a mark, the server saw the mark appear, and it
     * appeared inside the assessment's own window.
     */
    static boolean counts(SchemeAssessment assessment, Instant now) {
        if (assessment.getGrade() == null || assessment.getFirstGradedAt() == null) {
            return false;
        }
        Optional<LocalDate> due = dueDate(assessment.getDueDate());
        if (due.isEmpty()) {
            // No parseable due date means no window to check against, and a row that cannot be
            // time-locked is not evidence. Accepting it would be the whole defence's escape hatch:
            // clear the date, backfill the term. A student who wants the row to count fills the
            // date in, which is a thing the app already asks them to do.
            return false;
        }
        Instant opens = due.get().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant closes = opens.plus(ENTRY_WINDOW);
        Instant graded = assessment.getFirstGradedAt();

        return !graded.isBefore(opens) && !graded.isAfter(closes) && !graded.isAfter(now);
    }

    /**
     * The multiplier that puts a scheme's weights on a 0–100 scale, or empty if they are not a
     * whole course either way.
     *
     * <p>Both conventions are live in stored data: the client normalises what the user types into
     * fractions summing to 1.0, while extracted syllabus weights come through as percentages
     * summing to 100. Rather than guessing which one a table uses, this accepts a sum that is close
     * to either and rejects everything else — which is exactly the well-formedness question being
     * asked, so the ambiguity costs nothing.
     */
    static Optional<Double> weightScale(List<SchemeAssessment> assessments) {
        double sum = 0.0;
        for (SchemeAssessment assessment : assessments) {
            if (assessment != null) {
                sum += weightOf(assessment);
            }
        }
        if (Math.abs(sum - 100.0) <= WEIGHT_TOLERANCE) {
            return Optional.of(1.0);
        }
        if (Math.abs(sum - 1.0) <= FRACTION_TOLERANCE) {
            return Optional.of(100.0);
        }
        return Optional.empty();
    }

    private static double weightOf(SchemeAssessment assessment) {
        return assessment.getWeight() == null ? 0.0 : assessment.getWeight().doubleValue();
    }

    /**
     * The due date, from a field that is a free-form string.
     *
     * <p>The client writes {@code YYYY-MM-DD}, but the syllabus extractor can produce a
     * comma-joined list of dates for a recurring assessment. The first ISO date in the value is the
     * one used, which for a recurring row is its first occurrence — the earliest point at which a
     * grade for it could legitimately exist.
     */
    static Optional<LocalDate> dueDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = ISO_DATE.matcher(raw);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(matcher.group(1)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
