package org.tracker.gpatracker.accounts.dto;

import java.math.BigDecimal;

public class GPACalculatorResponse {
    private BigDecimal gpa;
    private BigDecimal totalCredits;

    public BigDecimal getGpa() {
        return gpa;
    }

    public void setGpa(BigDecimal gpa) {
        this.gpa = gpa;
    }

    public BigDecimal getTotalCredits() {
        return totalCredits;
    }

    public void setTotalCredits(BigDecimal totalCredits) {
        this.totalCredits = totalCredits;
    }
}
