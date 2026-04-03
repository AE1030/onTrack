package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.tracker.gpatracker.syllabus.exception.SyllabusErrorType;

import java.time.Instant;

@Document(collection = "syllabusExtractionJobs")
public class SyllabusExtractionJob {
    @Id
    private String id;
    private Long studentId;
    private String courseCode;
    private String term;
    private JobStatus status;
    private SyllabusErrorType errorType;
    private String error;
    private Integer remainingUploads;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
