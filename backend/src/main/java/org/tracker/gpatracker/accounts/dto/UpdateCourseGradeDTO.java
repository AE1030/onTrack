package org.tracker.gpatracker.accounts.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public class UpdateCourseGradeDTO {
    @NotBlank(message = "Course code is required")
    @Size(max = 100, message = "Course code is too long")
    private String courseCode;

    /**
     * The term the client believes it is editing. Not a choice of where to write: the server
     * refuses anything but the current term, so this is how a stale screen is caught.
     */
    @NotBlank(message = "Term is required")
    @Size(max = 32, message = "Term is too long")
    private String term;

    @NotNull(message = "Grade is required")
    @DecimalMin(value = "0.0", message = "Grade must be at least 0")
    private BigDecimal grade;

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public BigDecimal getGrade() {
        return grade;
    }

    public void setGrade(BigDecimal grade) {
        this.grade = grade;
    }
}
