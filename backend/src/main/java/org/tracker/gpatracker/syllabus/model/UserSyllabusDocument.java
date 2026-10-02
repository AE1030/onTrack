package org.tracker.gpatracker.syllabus.model;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Filter;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwned;

/**
 * A student's own extraction of a syllabus, which takes precedence over the shared catalog for that
 * student.
 *
 * <p>Cannot extend {@code UserOwnedEntity}: its {@code student_id} is already mapped as part of the
 * {@link StudentCourseTermId} below, and a second mapping of the same column is a boot failure. The
 * owner is read out of the key instead and the filter is declared here directly — the same shape
 * {@code CourseEnrollement} uses for the same reason.
 *
 * <p>The {@code @Filter} is not inherited from anywhere and is what actually enforces isolation.
 * Omitting it produces no error and no warning, just an unfiltered table;
 * {@code TenantMappingTest.everyUserOwnedEntityIsFiltered} exists to catch exactly that.
 *
 * <p>There is no {@code @PrePersist} owner stamp, because there is nothing safe to stamp: the owner
 * is half the primary key, so it must be supplied before the row exists.
 * {@code GeminiSyllabusExtractionService} sets it when it builds the key.
 */
@Entity
@Table(name = "user_syllabus_extraction")
@Filter(name = OwnerFilter.NAME)
public class UserSyllabusDocument extends AbstractSyllabusDocument implements UserOwned {

    @EmbeddedId
    private StudentCourseTermId id;

    public StudentCourseTermId getId() {
        return id;
    }

    public void setId(StudentCourseTermId id) {
        this.id = id;
    }

    public Long getStudentId() {
        return id == null ? null : id.getStudentId();
    }

    public void setStudentId(Long studentId) {
        key().setStudentId(studentId);
    }

    @Transient
    @Override
    public Long getOwnerId() {
        return getStudentId();
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

    private StudentCourseTermId key() {
        if (id == null) {
            id = new StudentCourseTermId();
        }
        return id;
    }
}
