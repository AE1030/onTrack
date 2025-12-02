package org.tracker.gpatracker.service.GPAUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeFinder {
    private final Pattern coursePattern = Pattern.compile("[[A-Z]{3,} ]+[0-9][A-Z0-9]{2}[0-9][A|B]*|[[A-Z]{2,} ]+[A-Z]*[0-9]{3,}[A|B]*");

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
}
