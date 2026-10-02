package org.tracker.gpatracker.syllabus.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Filter;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwned;

import java.time.Instant;

/**
 * How many syllabus uploads a student has left.
 *
 * <p>Self-scoping: the owner <em>is</em> the primary key, so a quota cannot be fetched cross-tenant
 * except by asking for someone else's id outright. It implements read-only {@link UserOwned} and
 * carries the filter, so the owner condition still applies to every query, but there is no
 * {@code @PrePersist} stamp — the owner must be supplied before the row can exist at all.
 *
 * <p>Does not extend {@code BaseEntity}. This one never carried the shared audit timestamps and
 * keeps its own hand-set {@code updatedAt}, which {@code SyllabusUploadQuotaService} writes on every
 * change. Adding {@code createdAt} here would mean a migration for no reader.
 */
@Entity
@Table(name = "syllabus_upload_quota")
@Filter(name = OwnerFilter.NAME)
public class SyllabusUploadQuota implements UserOwned {

    @Id
    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "remaining_uploads", nullable = false)
    private int remainingUploads;

    @Column(name = "last_upload_at")
    private Instant lastUploadAt;

    @Column(name = "updated_at")
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
