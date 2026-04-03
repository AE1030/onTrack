package org.tracker.gpatracker.accounts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AddCurrentCourseDTO {
    @NotBlank(message = "Course code is required")
    @Size(max = 20, message = "Course code is too long")
    String courseCode;

    // grade is nullable — frontend sends null for empty grades
    String grade;

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }
}
