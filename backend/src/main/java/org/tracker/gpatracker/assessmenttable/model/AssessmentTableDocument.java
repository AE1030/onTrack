package org.tracker.gpatracker.assessmenttable.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Document(collection = "assessmentTable")
public class AssessmentTableDocument {
    @Id
    private String id;
    private String courseCode;
    private String term;
    private Long studentId;
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

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public List<AssessmentScheme> getSchemes() {
        return schemes;
    }

    public void setSchemes(List<AssessmentScheme> schemes) {
        this.schemes = schemes;
    }
}
