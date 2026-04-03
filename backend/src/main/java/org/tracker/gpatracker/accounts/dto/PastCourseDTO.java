package org.tracker.gpatracker.accounts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class PastCourseDTO {
    @NotBlank(message = "Course name is required")
    @Size(max = 100, message = "Course name is too long")
    private String courseName;

    @NotBlank(message = "Credits is required")
    private String credits;

    @NotBlank(message = "Grade is required")
    private String grade;

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getCredits() {
        return credits;
    }

    public void setCredits(String credits) {
        this.credits = credits;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }
}
