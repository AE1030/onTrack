package org.tracker.gpatracker.service.GPAUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

public class GPACalc {

    public Double getGPA(List<GPABuilder>courseList, Map<String, Double> gradeDict) {
        BigDecimal pointsEarned = BigDecimal.ZERO;
        BigDecimal pointsAttm = BigDecimal.ZERO;

        for (GPABuilder b : courseList) {
            Double gradeVal = gradeDict.get(b.getGrade());
            if (gradeVal == null) {
                continue;
            }
            BigDecimal grade = BigDecimal.valueOf(gradeVal);
            BigDecimal units = new BigDecimal(b.getUnits());

            if (units.compareTo(BigDecimal.ZERO) == 0) {
                continue; // skip this course entirely
            }

            pointsEarned = pointsEarned.add(grade.multiply(units));
            pointsAttm = pointsAttm.add(units);
        }

        if (pointsAttm.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }

        BigDecimal gpa = pointsEarned.divide(pointsAttm, 3, RoundingMode.HALF_UP);
        return gpa.setScale(1, RoundingMode.HALF_UP).doubleValue();


    }

}
