package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.tracker.gpatracker.syllabus.exception.SyllabusErrorType;
import org.tracker.gpatracker.tenancy.mongo.UserOwnedDocument;

@Document(collection = "syllabusExtractionJobs")
@CompoundIndex(name = "idx_extraction_job_student", def = "{'studentId': 1}")
public class SyllabusExtractionJob extends UserOwnedDocument {
    @Id
    private String id;
    private String courseCode;
    private String term;
    private JobStatus status;
    private SyllabusErrorType errorType;
    private String error;
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
