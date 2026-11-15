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

    @Test
    @DisplayName("4.0 values map back to the 12-point scale, inverting toFourPoint")
    void toTwelvePointInverts() {
        assertThat(GpaScale.toTwelvePoint(new BigDecimal("4.0"))).isEqualByComparingTo("12.00");
        assertThat(GpaScale.toTwelvePoint(new BigDecimal("3.9"))).isEqualByComparingTo("11.00");
        assertThat(GpaScale.toTwelvePoint(new BigDecimal("3.0"))).isEqualByComparingTo("8.00");
        assertThat(GpaScale.toTwelvePoint(new BigDecimal("3.46"))).isEqualByComparingTo("9.40");
        assertThat(GpaScale.toTwelvePoint(new BigDecimal("0"))).isEqualByComparingTo("0.00");
        for (String twelve : new String[] {"0", "2.5", "6.25", "9.4", "10.75", "12"}) {
            BigDecimal four = GpaScale.toFourPoint(new BigDecimal(twelve));
            assertThat(GpaScale.toFourPoint(GpaScale.toTwelvePoint(four))).isEqualByComparingTo(four);
        }
    }

    @Test
    @DisplayName("toTwelvePoint clamps out of range values and passes null through")
    void toTwelvePointClamps() {
        assertThat(GpaScale.toTwelvePoint(new BigDecimal("5"))).isEqualByComparingTo("12.00");
        assertThat(GpaScale.toTwelvePoint(new BigDecimal("-1"))).isEqualByComparingTo("0.00");
        assertThat(GpaScale.toTwelvePoint(null)).isNull();
    }
}
