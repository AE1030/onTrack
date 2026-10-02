package org.tracker.gpatracker.syllabus.model;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The shared, trusted syllabus catalog. Not owned by anyone, and deliberately outside the tenant
 * contract — it must stay readable by every student, so it implements neither {@code UserOwned} nor
 * carries an owner filter. {@code TenantMappingTest} asserts that on purpose, so a future tidy-up
 * cannot quietly scope it and hide the catalog from everyone.
 *
 * <p>Written by {@code tools/syllabus-pipeline}, never by this application. One row per section,
 * keyed by {@link CourseTermDocId}.
 *
 * <p>{@code docCode} is exposed as an accessor over the key rather than as its own field. On Mongo
 * it was stored twice — inside the {@code _id} and again at the top level — so callers could read it
 * without unpacking the key. In Postgres that duplication is not available: {@code doc_code} is a
 * primary-key column, and mapping it a second time fails at boot.
 */
@Entity
@Table(name = "syllabus")
public class SyllabusDocument extends AbstractSyllabusDocument {

    @EmbeddedId
    private CourseTermDocId id;

    public CourseTermDocId getId() {
        return id;
    }

    public void setId(CourseTermDocId id) {
        this.id = id;
    }

    @Override
    public String getCourseCode() {
        return id == null ? null : id.getCourseCode();
    }

    @Override
    public void setCourseCode(String courseCode) {
        key().setCourseCode(courseCode);
    }

    @Override
    public String getTerm() {
        return id == null ? null : id.getTerm();
    }

    @Override
    public void setTerm(String term) {
        key().setTerm(term);
    }

    public String getDocCode() {
        return id == null ? null : id.getDocCode();
    }

    public void setDocCode(String docCode) {
        key().setDocCode(docCode);
    }

    private CourseTermDocId key() {
        if (id == null) {
            id = new CourseTermDocId();
        }
        return id;
    }
}
