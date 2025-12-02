package org.tracker.gpatracker.service.GPAUtils;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradeFinder {

    private final Pattern letterGradePattern = Pattern.compile(" [A-D][+|-]?$| F$|^[A-D][+|-]?$|^F$");
    private final Pattern otherGradePattern = Pattern.compile(" AUD$| NC$| P$| MT$| COM$| W$| T$|^AUD$|^NC$|^P$|^MT$|^COM$|^W$|^T$");

    public boolean containsGrade(String line) {
        return letterGradePattern.matcher(line).find() || otherGradePattern.matcher(line).find();
    }

    public boolean containsLetterGrade(String line) {
        return letterGradePattern.matcher(line).find();
    }

    public String getGrade(String line) {
        Matcher matcher = letterGradePattern.matcher(line);
        if (matcher.find()) {
            return matcher.group().trim();
        }

        matcher = otherGradePattern.matcher(line);
        if (matcher.find()) {
            return matcher.group().trim();
        }

        return "";
    }


}
