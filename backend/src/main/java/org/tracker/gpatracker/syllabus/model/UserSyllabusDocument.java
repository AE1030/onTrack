package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;
import org.tracker.gpatracker.tenancy.mongo.OwnedDocument;

/**
 * A student's own extraction of a syllabus.
 *
 * <p>Cannot extend {@code UserOwnedDocument} — Java allows one superclass and this already extends
 * {@link AbstractSyllabusDocument}, whose sibling {@code SyllabusDocument} must stay unowned. So it
 * implements {@link OwnedDocument} directly, which is all the listener actually keys on.
 *
 * <p>The owner appears twice: inside {@link StudentCourseTermId}, where it makes the key unique and
 * every tenant lookup {@code _id}-index-covered, and as a top-level {@code studentId}, where the
 * generic stamp/assert listener can find it without knowing this class's key shape. Eight bytes of
 * duplication buys uniformity with the other four owned collections.
 *
 * <p>The top-level field was stored as {@code student_id} before this change and is now plain
 * {@code studentId}, matching every other collection. That rename is free only because the
 * collection is emptied as part of the {@code _id} reshape.
 */
@Document(collection = "userSyllabiExtraction")
public class UserSyllabusDocument extends AbstractSyllabusDocument implements OwnedDocument {

    @Id
    private StudentCourseTermId id;

    private Long studentId;

    public StudentCourseTermId getId() {
        return id;
    }

    public void setId(StudentCourseTermId id) {
        this.id = id;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    @Transient
    @Override
    public Long getOwnerId() {
        return studentId;
    }

    @Transient
    @Override
    public void setOwnerId(Long ownerId) {
        this.studentId = ownerId;
    }
}
