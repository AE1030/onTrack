package org.tracker.gpatracker.service.GPAUtils;
import java.util.HashMap;
import java.util.Map;

public class GradeDict {
    private final Map<String, Double> macGrades = new HashMap<>();
    private final Map<String, Double> standardGrades = new HashMap<>();

    public GradeDict() {
        macGrades.put("A+", 12.0);
        macGrades.put("A", 11.0);
        macGrades.put("A-", 10.0);
        macGrades.put("B+", 9.0);
        macGrades.put("B", 8.0);
        macGrades.put("B-", 7.0);
        macGrades.put("C+", 6.0);
        macGrades.put("C", 5.0);
        macGrades.put("C-", 4.0);
        macGrades.put("D+", 3.0);
        macGrades.put("D", 2.0);
        macGrades.put("D-", 1.0);
        macGrades.put("F", 0.0);

        standardGrades.put("A+", 4.0);
        standardGrades.put("A", 3.9);
        standardGrades.put("A-", 3.7);
        standardGrades.put("B+", 3.3);
        standardGrades.put("B", 3.0);
        standardGrades.put("B-", 2.7);
        standardGrades.put("C+", 2.3);
        standardGrades.put("C", 2.0);
        standardGrades.put("C-", 1.7);
        standardGrades.put("D+", 1.3);
        standardGrades.put("D", 1.0);
        standardGrades.put("D-", 0.7);
        standardGrades.put("F", 0.0);
    }

    public Map<String, Double> getMacGradeDict() {
        return macGrades;
    }

    public Map<String, Double> getStandardGradeDict() { return standardGrades; }


}