package org.tracker.gpatracker.accounts.service.gpautils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class GpaScaleTest {

    @Test
    @DisplayName("whole 12-point values land on the letter's 4.0 points")
    void letterPoints() {
        assertThat(GpaScale.toFourPoint(new BigDecimal("12"))).isEqualByComparingTo("4.00");
        assertThat(GpaScale.toFourPoint(new BigDecimal("11"))).isEqualByComparingTo("3.90");
        assertThat(GpaScale.toFourPoint(new BigDecimal("8"))).isEqualByComparingTo("3.00");
        assertThat(GpaScale.toFourPoint(new BigDecimal("0"))).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("values between letters interpolate, and out of range values clamp")
    void interpolatesAndClamps() {
        assertThat(GpaScale.toFourPoint(new BigDecimal("9.5"))).isEqualByComparingTo("3.50");
        assertThat(GpaScale.toFourPoint(new BigDecimal("13"))).isEqualByComparingTo("4.00");
        assertThat(GpaScale.toFourPoint(new BigDecimal("-1"))).isEqualByComparingTo("0.00");
        assertThat(GpaScale.toFourPoint(null)).isNull();
    }
}
