package org.tracker.gpatracker.tenancy.mongo;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

/**
 * Mongo counterpart to {@code BaseEntity}: audit timestamps for every document.
 *
 * <p>These are populated by Spring Data auditing, which {@code MongoAuditingConfig} switches on.
 * Mongo timestamps used to be set by hand at each call site, and inconsistently.
 *
 * <p>This is a separate hierarchy from the JPA one on purpose — a {@code @Document} cannot extend
 * a JPA {@code @MappedSuperclass}, so the two trees cannot be unified no matter how similar the
 * fields look.
 */
public abstract class BaseDocument {

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
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
