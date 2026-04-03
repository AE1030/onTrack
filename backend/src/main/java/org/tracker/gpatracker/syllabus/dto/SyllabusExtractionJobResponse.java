package org.tracker.gpatracker.syllabus.dto;

import org.tracker.gpatracker.syllabus.model.JobStatus;
import org.tracker.gpatracker.syllabus.model.SyllabusExtractionJob;
import org.tracker.gpatracker.syllabus.exception.SyllabusErrorType;

import java.time.Instant;

public class SyllabusExtractionJobResponse {
    private String id;
    private String courseCode;
    private String term;
    private JobStatus status;
    private SyllabusErrorType errorType;
    private String error;
    private Integer remainingUploads;
    private Instant createdAt;
    private Instant updatedAt;

    public static SyllabusExtractionJobResponse from(SyllabusExtractionJob job) {
        SyllabusExtractionJobResponse response = new SyllabusExtractionJobResponse();
        response.setId(job.getId());
        response.setCourseCode(job.getCourseCode());
        response.setTerm(job.getTerm());
        response.setStatus(job.getStatus());
        response.setErrorType(job.getErrorType());
        response.setError(job.getError());
        response.setRemainingUploads(job.getRemainingUploads());
        response.setCreatedAt(job.getCreatedAt());
        response.setUpdatedAt(job.getUpdatedAt());
        return response;
    }

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
