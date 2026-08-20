package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;
import org.tracker.gpatracker.tenancy.UserOwned;

import java.time.Instant;

/**
 * Self-scoping: the owner <em>is</em> the {@code _id}, so a quota cannot be fetched cross-tenant
 * except by asking for someone else's id outright. It implements read-only {@link UserOwned} rather
 * than {@code OwnedDocument} so the load-time assert still catches that, while the write-time stamp
 * skips it — Mongo's {@code _id} is immutable, so there is nothing safe for a stamp to set.
 */
@Document(collection = "syllabusUploadQuotas")
public class SyllabusUploadQuota implements UserOwned {
    @Id
    private Long studentId;
    private int remainingUploads;
    private Instant lastUploadAt;
    private Instant updatedAt;

    @Transient
    @Override
    public Long getOwnerId() {
        return studentId;
    }

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
