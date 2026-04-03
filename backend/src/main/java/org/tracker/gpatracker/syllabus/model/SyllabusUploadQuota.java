package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "syllabusUploadQuotas")
public class SyllabusUploadQuota {
    @Id
    private Long studentId;
    private int remainingUploads;
    private Instant lastUploadAt;
    private Instant updatedAt;

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public int getRemainingUploads() {
        return remainingUploads;
    }

    public void setRemainingUploads(int remainingUploads) {
        this.remainingUploads = remainingUploads;
    }

    public Instant getLastUploadAt() {
        return lastUploadAt;
    }

    public void setLastUploadAt(Instant lastUploadAt) {
        this.lastUploadAt = lastUploadAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
