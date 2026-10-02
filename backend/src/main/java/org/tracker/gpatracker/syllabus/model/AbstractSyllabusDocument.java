package org.tracker.gpatracker.syllabus.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.tracker.gpatracker.tenancy.BaseEntity;

/**
 * Fields common to the shared syllabus catalog and to a student's own extraction.
 *
 * <p>{@code courseCode} and {@code term} are declared here as <em>abstract accessors</em>, not as
 * mapped columns. Both subclasses carry them inside their composite key — the catalog on
 * course + term + doc, a student's extraction on student + course + term — and mapping the same
 * column twice is a boot failure, not a warning. Each subclass therefore reads and writes them
 * through its own key, while callers keep a single type to program against.
 *
 * <p>The key is likewise not declared here. Pulling it up would reinstate the shape that let two
 * students collide; see {@link StudentCourseTermId}.
 *
 * <p>{@code assessments} goes through {@link SyllabusAssessmentsJsonConverter} rather than a plain
 * {@code @JdbcTypeCode(SqlTypes.JSON)} mapping, because its keys are snake_case and shared with the
 * Python pipeline. {@code extraction} needs no converter: {@code model} and {@code temperature} are
 * single words, so the two naming conventions agree on them.
 */
@MappedSuperclass
public abstract class AbstractSyllabusDocument extends BaseEntity {

    @Convert(converter = SyllabusAssessmentsJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assessments")
    private Assessments assessments;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extraction")
    private ExtractionMetadata extraction;

    /** Read from this document's composite key. */
    public abstract String getCourseCode();

    /** Written into this document's composite key, creating it if absent. */
    public abstract void setCourseCode(String courseCode);

    /** Read from this document's composite key. */
    public abstract String getTerm();

    /** Written into this document's composite key, creating it if absent. */
    public abstract void setTerm(String term);

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
