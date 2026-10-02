package org.tracker.gpatracker.syllabus.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.tracker.gpatracker.syllabus.exception.SyllabusErrorType;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwnedEntity;

/**
 * The status of one student's syllabus PDF extraction.
 *
 * <p>Both enums are {@code EnumType.STRING}. Ordinals would make reordering either enum silently
 * reinterpret every stored row, and {@link SyllabusErrorType} in particular is the sort of list
 * people insert into alphabetically.
 */
@Entity
@Table(
        name = "syllabus_extraction_job",
        indexes = @Index(name = "idx_extraction_job_student", columnList = "student_id"))
@Filter(name = OwnerFilter.NAME)
public class SyllabusExtractionJob extends UserOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "course_code", length = 64)
    private String courseCode;

    @Column(name = "term", length = 64)
    private String term;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", length = 32)
    private SyllabusErrorType errorType;

    @Column(name = "error")
    private String error;

    @Column(name = "remaining_uploads")
    private Integer remainingUploads;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public SyllabusErrorType getErrorType() {
        return errorType;
    }

    public void setErrorType(SyllabusErrorType errorType) {
        this.errorType = errorType;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Integer getRemainingUploads() {
        return remainingUploads;
    }

    public void setRemainingUploads(Integer remainingUploads) {
        this.remainingUploads = remainingUploads;
    }
}
