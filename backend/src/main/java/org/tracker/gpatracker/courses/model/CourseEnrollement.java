package org.tracker.gpatracker.courses.model;

import jakarta.persistence.*;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.service.BigDecimalGradeEncryptionConverter;

import java.math.BigDecimal;

@Entity
@Table(
        name = "course_enrollement",
        uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "course_id"})
)
public class CourseEnrollement {
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
}
