package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Field;

public abstract class AbstractSyllabusDocument {
    @Id
    private CourseTermId id;
    private Assessments assessments;
    @Field("course_code")
    private String courseCode;

    @Field("term")
    private String term;
    private ExtractionMetadata extraction;

    public CourseTermId getId() {
        return id;
    }

    public void setId(CourseTermId id) {
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

    public Assessments getAssessments() {
        return assessments;
    }

    public void setAssessments(Assessments assessments) {
        this.assessments = assessments;
    }

    public ExtractionMetadata getExtraction() {
        return extraction;
    }

    public void setExtraction(ExtractionMetadata extraction) {
        this.extraction = extraction;
    }
}
