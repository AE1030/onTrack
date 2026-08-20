package org.tracker.gpatracker.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Audit timestamps, shared by every JPA entity.
 *
 * <p>Replaces three inconsistent hand-rolled conventions that had grown across the codebase
 * ({@code createdDate}/{@code lastModifiedDate}, {@code createdAt}/{@code lastModifiedAt}, and
 * {@code createdAt} alone).
 *
 * <p>Both columns are nullable on purpose. {@code ddl-auto=update} adds new columns but never
 * back-fills them, so rows written before this change have no value; a {@code NOT NULL}
 * constraint could not be applied without a migration.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

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
