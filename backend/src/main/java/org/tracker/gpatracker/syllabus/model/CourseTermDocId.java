package org.tracker.gpatracker.syllabus.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Primary key for the shared syllabus catalog.
 *
 * <p>The catalog used to key on course + term alone. Simple Syllabus publishes one document per
 * <em>section</em> and gives each its own {@code doc_code}, so every section of a course shared
 * that key, and an upsert meant the last section scraped silently overwrote the rest. A first-year
 * course with five sections stored one of them, chosen by whichever the scrape happened to reach
 * last.
 *
 * <p>Nothing new is parsed to fix this: {@code doc_code} already <em>is</em> the section's
 * identity, and the old key simply discarded a distinction the source data makes. Reads that want
 * "the syllabus for this course this term" go through {@code TrustedSyllabusProvider}, which
 * applies an explicit preference rule instead of inheriting whatever was written last.
 *
 * <p>Column names stay snake_case because {@code tools/syllabus-pipeline} writes this table
 * directly. It is the one part of the schema with a second writer in another language.
 */
@Embeddable
public class CourseTermDocId implements Serializable {

    @Column(name = "course_code", nullable = false, length = 64)
    private String courseCode;

    @Column(name = "term", nullable = false, length = 64)
    private String term;

    @Column(name = "doc_code", nullable = false, length = 128)
    private String docCode;

    public CourseTermDocId() {
    }

    public CourseTermDocId(String courseCode, String term, String docCode) {
        this.courseCode = courseCode;
        this.term = term;
        this.docCode = docCode;
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

    public String getDocCode() {
        return docCode;
    }

    public void setDocCode(String docCode) {
        this.docCode = docCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CourseTermDocId that = (CourseTermDocId) o;
        return Objects.equals(courseCode, that.courseCode)
                && Objects.equals(term, that.term)
                && Objects.equals(docCode, that.docCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(courseCode, term, docCode);
    }
}
