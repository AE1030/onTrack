package org.tracker.gpatracker.courses.model;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwnedEntity;

import java.time.LocalDate;

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
// Must be declared here, on the concrete entity: Hibernate does not inherit @Filter from a
// @MappedSuperclass, and omitting it leaves the table silently unfiltered.
@Filter(name = OwnerFilter.NAME)
public class DueDateOverride extends UserOwnedEntity {
     @Id
     @GeneratedValue(strategy = GenerationType.IDENTITY)
     private Long id;
     private String courseCode;
     private String assessmentName;
     private LocalDate proposedDueDate;

    // The owner lives in UserOwnedEntity.ownerId, mapped to the same student_id column.

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

}
