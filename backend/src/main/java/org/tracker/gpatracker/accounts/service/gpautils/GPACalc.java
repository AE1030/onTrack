package org.tracker.gpatracker.accounts.service.gpautils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

public class GPACalc {

    public BigDecimal getGPA(List<GPABuilder> courseList, Map<String, BigDecimal> gradeDict) {
        BigDecimal pointsEarned = BigDecimal.ZERO;
        BigDecimal pointsAttm = BigDecimal.ZERO;

        for (GPABuilder b : courseList) {
            BigDecimal gradeVal = gradeDict.get(b.getGrade());
            BigDecimal units = new BigDecimal(b.getUnits());

            if (gradeVal != null && units.compareTo(BigDecimal.ZERO) != 0) {
                pointsEarned = pointsEarned.add(gradeVal.multiply(units));
                pointsAttm = pointsAttm.add(units);
            }
        }

        if (pointsAttm.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return pointsEarned.divide(pointsAttm, 3, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
