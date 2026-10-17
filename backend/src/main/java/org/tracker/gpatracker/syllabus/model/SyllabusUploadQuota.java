package org.tracker.gpatracker.syllabus.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Filter;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwnedEntity;

import java.time.Instant;

/**
 * How many syllabus uploads a student has used in one term.
 *
 * <p>One row per student, enforced by the unique constraint on {@code student_id}. The row counts
 * uploads for the term in {@link #term}; when the current term moves on, the service resets the
 * count and repoints the row rather than adding a new one, so no scheduled job is needed.
 */
@Entity
@Table(
        name = "syllabus_upload_quota",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_syllabus_upload_quota_student",
                columnNames = "student_id"))
@Filter(name = OwnerFilter.NAME)
public class SyllabusUploadQuota extends UserOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The term {@link #uploadsUsed} counts for, in {@code app.current-term} form, e.g. "Fall 2026". */
    @Column(name = "term", length = 64)
    private String term;

    @Column(name = "uploads_used", nullable = false)
    private int uploadsUsed;

    @Column(name = "last_upload_at")
    private Instant lastUploadAt;

    public Long getId() {
        return id;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public int getUploadsUsed() {
        return uploadsUsed;
    }

    public void setUploadsUsed(int uploadsUsed) {
        this.uploadsUsed = uploadsUsed;
    }

    public Instant getLastUploadAt() {
        return lastUploadAt;
    }

    public void setLastUploadAt(Instant lastUploadAt) {
        this.lastUploadAt = lastUploadAt;
    }
}
