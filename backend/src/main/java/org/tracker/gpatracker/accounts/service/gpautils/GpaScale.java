package org.tracker.gpatracker.accounts.service.gpautils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converts a McMaster 12-point GPA to the 4.0 scale.
 *
 * <p>The two scales are not proportional (an 11 is a 3.9, not a 3.67), so this follows the per-letter
 * points in {@link GradeDict} and interpolates linearly between neighbouring letters. Used where only a
 * 12-point value exists, such as a leaderboard target, and the dashboard still needs both scales.
 */
public final class GpaScale {

    /** Index is the 12-point value, from F (0) to A+ (12); the entry is the matching 4.0 value. */
    private static final double[] FOUR_POINT = {
            0.0, 0.7, 1.0, 1.3, 1.7, 2.0, 2.3, 2.7, 3.0, 3.3, 3.7, 3.9, 4.0
    };

    private GpaScale() {
    }

    public static BigDecimal toFourPoint(BigDecimal gpa12) {
        if (gpa12 == null) {
            return null;
        }
        double value = Math.min(Math.max(gpa12.doubleValue(), 0.0), 12.0);
        int lower = (int) Math.floor(value);
        if (lower >= 12) {
            return BigDecimal.valueOf(FOUR_POINT[12]).setScale(2, RoundingMode.HALF_UP);
        }
        double fraction = value - lower;
        double interpolated = FOUR_POINT[lower] + fraction * (FOUR_POINT[lower + 1] - FOUR_POINT[lower]);
        return BigDecimal.valueOf(interpolated).setScale(2, RoundingMode.HALF_UP);
    }
}
