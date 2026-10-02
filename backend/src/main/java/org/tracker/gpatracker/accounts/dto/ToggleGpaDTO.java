package org.tracker.gpatracker.accounts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ToggleGpaDTO {
    @NotBlank(message = "Course code is required")
    @Size(max = 20, message = "Course code is too long")
    private String courseCode;

    /** See {@link UpdateCourseGradeDTO#getTerm()}. Checked, not honoured. */
    @NotBlank(message = "Term is required")
    @Size(max = 32, message = "Term is too long")
    private String term;

    private boolean includeInGpa;

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

    public boolean isIncludeInGpa() {
        return includeInGpa;
    }

    public void setIncludeInGpa(boolean includeInGpa) {
        this.includeInGpa = includeInGpa;
    }
}
