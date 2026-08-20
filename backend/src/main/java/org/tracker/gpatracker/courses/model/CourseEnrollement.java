package org.tracker.gpatracker.courses.model;

import jakarta.persistence.*;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.service.BigDecimalGradeEncryptionConverter;
import org.hibernate.annotations.Filter;
import org.tracker.gpatracker.tenancy.BaseEntity;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwned;

import java.math.BigDecimal;

@Entity
@Table(
        name = "course_enrollement",
        uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "course_id"})
)
// Cannot extend UserOwnedEntity: student_id is already mapped by the @EmbeddedId below, and a
// second mapping of the same column is a boot failure. The owner is read out of the key instead,
// and the filter is declared here directly.
@Filter(name = OwnerFilter.NAME)
public class CourseEnrollement extends BaseEntity implements UserOwned {
    @EmbeddedId
    CourseEnrollementKey id;
    @ManyToOne
    @MapsId("studentId")
    @JoinColumn(name = "student_id")
    private Student students;

    @ManyToOne
    @MapsId("courseId")
    @JoinColumn(name = "course_id")
    private Course courses;

    @Column(nullable = true)
    @Convert(converter = BigDecimalGradeEncryptionConverter.class)
    private BigDecimal grade;

    @Column(nullable = false)
    private boolean includeInGpa = true;

    public BigDecimal getGrade() {
        return grade;
    }

    public void setGrade(BigDecimal grade) {
        this.grade = grade;
    }

    public boolean isIncludeInGpa() {
        return includeInGpa;
    }

    public void setIncludeInGpa(boolean includeInGpa) {
        this.includeInGpa = includeInGpa;
    }

    public CourseEnrollement() {
        // Required by JPA
    }

    public CourseEnrollementKey getId() {
        return id;
    }

    public void setId(CourseEnrollementKey id) {
        this.id = id;
    }

    public Course getCourses() {
        return courses;
    }

    public void setCourses(Course courses) {
        this.courses = courses;
    }

    public Student getStudents() {
        return students;
    }

    public void setStudents(Student students) {
        this.students = students;
    }

    /**
     * The owner comes from the composite key rather than a dedicated column — no {@code @PrePersist}
     * stamp is needed, because a row cannot exist without a key.
     */
    @Override
    public Long getOwnerId() {
        return id == null ? null : id.getStudentId();
    }
}
