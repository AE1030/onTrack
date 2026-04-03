package org.tracker.gpatracker.accounts.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class SetTargetGpaDTO {
    @NotNull(message = "Target GPA (4.0 scale) is required")
    @DecimalMin(value = "0.0", message = "Target GPA must be at least 0")
    @DecimalMax(value = "4.0", message = "Target GPA must be at most 4.0")
    private BigDecimal targetGpa4;

    @NotNull(message = "Target GPA (12.0 scale) is required")
    @DecimalMin(value = "0.0", message = "Target GPA must be at least 0")
    @DecimalMax(value = "12.0", message = "Target GPA must be at most 12.0")
    private BigDecimal targetGpa12;

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
