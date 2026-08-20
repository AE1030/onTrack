package org.tracker.gpatracker.syllabus.model;

import java.util.Objects;

/**
 * Primary key for {@code userSyllabiExtraction}.
 *
 * <p>The collection used to key on {@link CourseTermId} — course + term, with the owner as an
 * ordinary field. Two students who uploaded the same course in the same term therefore produced the
 * same {@code _id}, and {@code save()} upserts by {@code _id}, so the second silently overwrote the
 * first. That was never a historical accident that had worked itself out; it would have recurred
 * for the next pair of students to collide.
 *
 * <p>Putting the owner in the key fixes it structurally and, as a bonus, makes every tenant-scoped
 * lookup covered by the {@code _id} index that Mongo maintains for free.
 */
public class StudentCourseTermId {

    private Long studentId;
    private String courseCode;
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
