package org.tracker.gpatracker.assessmenttable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;

import java.util.List;

public class SaveAssessmentTableDTO {
    private String id;

    @NotBlank(message = "Course code is required")
    @Size(max = 20, message = "Course code is too long")
    private String courseCode;

    private List<AssessmentScheme> schemes;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public List<AssessmentScheme> getSchemes() {
        return schemes;
    }

    public void setSchemes(List<AssessmentScheme> schemes) {
        this.schemes = schemes;
    }
}
