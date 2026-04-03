package org.tracker.gpatracker.accounts.dto;

import java.math.BigDecimal;

public class DashboardDTO {
    private String username;
    private BigDecimal gpa4;
    private BigDecimal gpa12;
    private BigDecimal targetGpa4;
    private BigDecimal targetGpa12;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public BigDecimal getGpa4() {
        return gpa4;
    }

    public void setGpa4(BigDecimal gpa4) {
        this.gpa4 = gpa4;
    }

    public BigDecimal getGpa12() {
        return gpa12;
    }

    public void setGpa12(BigDecimal gpa12) {
        this.gpa12 = gpa12;
    }

    public BigDecimal getTargetGpa4() {
        return targetGpa4;
    }

    public void setTargetGpa4(BigDecimal targetGpa4) {
        this.targetGpa4 = targetGpa4;
    }

    public BigDecimal getTargetGpa12() {
        return targetGpa12;
    }

    public void setTargetGpa12(BigDecimal targetGpa12) {
        this.targetGpa12 = targetGpa12;
    }
}
