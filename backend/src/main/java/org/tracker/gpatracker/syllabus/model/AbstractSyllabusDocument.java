package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.mongodb.core.mapping.Field;
import org.tracker.gpatracker.tenancy.mongo.BaseDocument;

/**
 * Fields common to the shared syllabus catalog and to a student's own extraction.
 *
 * <p>The {@code @Id} deliberately is <em>not</em> declared here. The two subclasses key on
 * different things — the catalog on course + term, a student's extraction on student + course +
 * term — and pulling the id back up would reinstate the shape that let two students collide.
 */
public abstract class AbstractSyllabusDocument extends BaseDocument {
    private Assessments assessments;
    @Field("course_code")
    private String courseCode;

    @Field("term")
    private String term;
    private ExtractionMetadata extraction;

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
