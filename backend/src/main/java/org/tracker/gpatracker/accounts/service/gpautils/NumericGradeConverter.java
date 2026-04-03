package org.tracker.gpatracker.accounts.service.gpautils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class NumericGradeConverter {

    private NumericGradeConverter() {}

    /**
     * Converts a numeric percentage grade (e.g. 84.5) to a letter grade.
     * The numeric value is rounded to the nearest integer before mapping.
     */
    public static String toLetter(BigDecimal numeric) {
        if (numeric == null) return null;
        int rounded = numeric.setScale(0, RoundingMode.HALF_UP).intValue();

        if (rounded >= 90) return "A+";
        if (rounded >= 85) return "A";
        if (rounded >= 80) return "A-";
        if (rounded >= 77) return "B+";
        if (rounded >= 73) return "B";
        if (rounded >= 70) return "B-";
        if (rounded >= 67) return "C+";
        if (rounded >= 63) return "C";
        if (rounded >= 60) return "C-";
        if (rounded >= 57) return "D+";
        if (rounded >= 53) return "D";
        if (rounded >= 50) return "D-";
        return "F";
    }
}
