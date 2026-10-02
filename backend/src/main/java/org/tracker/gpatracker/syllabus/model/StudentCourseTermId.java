package org.tracker.gpatracker.syllabus.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Primary key for {@code user_syllabus_extraction}.
 *
 * <p>The collection this replaces used to key on course + term alone, with the owner as an ordinary
 * field. Two students who uploaded the same course in the same term therefore produced the same key,
 * and the write upserted by key, so the second silently overwrote the first. That was never a
 * historical accident that had worked itself out; it would have recurred for the next pair of
 * students to collide.
 *
 * <p>Putting the owner in the key fixes it structurally. It also means the owner column the tenant
 * filter matches on is part of the primary key, which is why {@link UserSyllabusDocument} implements
 * {@code UserOwned} directly rather than extending {@code UserOwnedEntity} — see the note there.
 */
@Embeddable
public class StudentCourseTermId implements Serializable {

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "course_code", nullable = false, length = 64)
    private String courseCode;

    @Column(name = "term", nullable = false, length = 64)
    private String term;

    public StudentCourseTermId() {
    }

    public StudentCourseTermId(Long studentId, String courseCode, String term) {
        this.studentId = studentId;
        this.courseCode = courseCode;
        this.term = term;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StudentCourseTermId that = (StudentCourseTermId) o;
        return Objects.equals(studentId, that.studentId)
                && Objects.equals(courseCode, that.courseCode)
                && Objects.equals(term, that.term);
    }

    @Override
    public int hashCode() {
        return Objects.hash(studentId, courseCode, term);
    }
}
