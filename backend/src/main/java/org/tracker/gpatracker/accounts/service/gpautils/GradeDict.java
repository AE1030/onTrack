package org.tracker.gpatracker.accounts.service.gpautils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public class GradeDict {
    private final Map<String, BigDecimal> macGrades = new HashMap<>();
    private final Map<String, BigDecimal> standardGrades = new HashMap<>();

    public GradeDict() {
        macGrades.put("A+", new BigDecimal("12.0"));
        macGrades.put("A", new BigDecimal("11.0"));
        macGrades.put("A-", new BigDecimal("10.0"));
        macGrades.put("B+", new BigDecimal("9.0"));
        macGrades.put("B", new BigDecimal("8.0"));
        macGrades.put("B-", new BigDecimal("7.0"));
        macGrades.put("C+", new BigDecimal("6.0"));
        macGrades.put("C", new BigDecimal("5.0"));
        macGrades.put("C-", new BigDecimal("4.0"));
        macGrades.put("D+", new BigDecimal("3.0"));
        macGrades.put("D", new BigDecimal("2.0"));
        macGrades.put("D-", new BigDecimal("1.0"));
        macGrades.put("F", new BigDecimal("0.0"));

        standardGrades.put("A+", new BigDecimal("4.0"));
        standardGrades.put("A", new BigDecimal("3.9"));
        standardGrades.put("A-", new BigDecimal("3.7"));
        standardGrades.put("B+", new BigDecimal("3.3"));
        standardGrades.put("B", new BigDecimal("3.0"));
        standardGrades.put("B-", new BigDecimal("2.7"));
        standardGrades.put("C+", new BigDecimal("2.3"));
        standardGrades.put("C", new BigDecimal("2.0"));
        standardGrades.put("C-", new BigDecimal("1.7"));
        standardGrades.put("D+", new BigDecimal("1.3"));
        standardGrades.put("D", new BigDecimal("1.0"));
        standardGrades.put("D-", new BigDecimal("0.7"));
        standardGrades.put("F", new BigDecimal("0.0"));
    }

    public Map<String, BigDecimal> getMacGradeDict() {
        return macGrades;
    }

    public Map<String, BigDecimal> getStandardGradeDict() { return standardGrades; }
}
