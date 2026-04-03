package org.tracker.gpatracker.accounts.service.gpautils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeFinder {
    private final Pattern coursePattern = Pattern.compile("\\b[A-Z]{2,} [1-9][A-Z\\d]{2}\\d\\d?[AB]?\\b");


    private final Pattern multiYearCoursePattern = Pattern.compile("^(.*\\d)([AB])$", Pattern.CASE_INSENSITIVE);

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

    public String normalizeMultiYearCourse(String courseCode) {
        if (courseCode == null) {
            return null;
        }
        String trimmedCode = courseCode.trim();
        Matcher matcher = multiYearCoursePattern.matcher(trimmedCode);
        if (matcher.matches()) {
            String baseCode = matcher.group(1).trim();
            return baseCode + " A/B";
        }
        return trimmedCode;
    }
}
