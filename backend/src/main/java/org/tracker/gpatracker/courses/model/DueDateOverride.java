package org.tracker.gpatracker.courses.model;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.tracker.gpatracker.accounts.model.Student;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "due_date_override",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_student_course_term_assessment",
                columnNames = {
                        "student_id",
                        "course_code",
                        "assessment_name"
                }
        )
)
@EntityListeners(org.springframework.data.jpa.domain.support.AuditingEntityListener.class)
public class DueDateOverride {
     @Id
     @GeneratedValue(strategy = GenerationType.IDENTITY)
     private Long id;
     private String courseCode;
     private String assessmentName;
     private LocalDate proposedDueDate;
     @CreatedDate
     @Column(updatable = false)
     private LocalDateTime createdAt;

     @LastModifiedDate
     private LocalDateTime lastModifiedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getAssessmentName() {
        return assessmentName;
    }

    public void setAssessmentName(String assessmentName) {
        this.assessmentName = assessmentName;
    }

    public LocalDate getProposedDueDate() {
        return proposedDueDate;
    }

    public void setProposedDueDate(LocalDate proposedDueDate) {
        this.proposedDueDate = proposedDueDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Student getStudent() {
        return student;
    }

    public void setStudent(Student student) {
        this.student = student;
    }
}
