package org.tracker.gpatracker.accounts.service.gpautils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UnitFinder {
    private final Pattern scorePattern = Pattern.compile("\\d*\\.0{2}/\\d*\\.0{2}|\\d*\\.0{2}");
    private static final Pattern uncompletedCoursePattern = Pattern.compile("[1-9]+\\d*\\.0{2}/0\\.0{2}");

    public boolean containsUnits(String line) {
        return scorePattern.matcher(line).find();
    }

    public String getUnits(String line) {
        Matcher m = scorePattern.matcher(line);
        if (!m.find()) {
            return "";
        }
        String units = line.substring(m.start(), m.end());
        String[] temp = units.split("/");
        return temp[0].strip();
    }

    public static boolean isUncompletedCourse(String line) {
        Matcher m = uncompletedCoursePattern.matcher(line);
        return m.find();
    }
}
