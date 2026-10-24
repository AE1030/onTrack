package org.tracker.gpatracker.accounts.service.gpautils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeFinder {
    /**
     * A course code as a transcript prints it: {@code COMPSCI 2C03}, or a multi-term half such as
     * {@code ARTSSCI 1D06A}.
     *
     * <p>The optional {@code S} covers the catalog's {@code A/B S} courses (multi-term courses that
     * may also be taken within a single term), in case the transcript prints the suffix:
     * {@code 4F06AS}, {@code 4F06BS} or {@code 4F06S}. Without it the trailing {@code \b} fails on
     * the letter after the A or B, and the whole line is not recognised as a course at all.
     */
    private final Pattern coursePattern = Pattern.compile("\\b[A-Z]{2,} [1-9][A-Z\\d]{2}\\d\\d?[AB]?S?\\b");


    private final Pattern multiYearCoursePattern = Pattern.compile("^(.*\\d)([AB])?(S)?$", Pattern.CASE_INSENSITIVE);

    public boolean containsCourse(String line) {
        return coursePattern.matcher(line).find();
    }
    public String getCourse(String line) {
        Matcher matcher = coursePattern.matcher(line);
        if (matcher.find()) {
            return line.substring(matcher.start(), matcher.end()).trim();
        }
        return "";
    }

    /**
     * The catalog's name for a transcript code. Both halves of a multi-term course map to one name:
     * {@code 1D06A} and {@code 1D06B} become {@code 1D06 A/B}; {@code 4F06AS}, {@code 4F06BS} and
     * {@code 4F06S} become {@code 4F06 A/B S}. Every other code is returned unchanged.
     *
     * <p>A bare {@code S} maps to {@code A/B S} because nothing in the catalog ends in S except
     * those courses.
     */
    public String normalizeMultiYearCourse(String courseCode) {
        if (courseCode == null) {
            return null;
        }
        String trimmedCode = courseCode.trim();
        Matcher matcher = multiYearCoursePattern.matcher(trimmedCode);
        if (matcher.matches() && (matcher.group(2) != null || matcher.group(3) != null)) {
            String baseCode = matcher.group(1).trim();
            return baseCode + (matcher.group(3) != null ? " A/B S" : " A/B");
        }
        return trimmedCode;
    }

    /** Whether a catalog code names a course that runs across two terms. */
    public static boolean isMultiTerm(String courseCode) {
        if (courseCode == null) {
            return false;
        }
        String trimmed = courseCode.trim();
        return trimmed.endsWith(" A/B") || trimmed.endsWith(" A/B S");
    }
}
