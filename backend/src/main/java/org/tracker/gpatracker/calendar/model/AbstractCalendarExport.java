package org.tracker.gpatracker.calendar.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.tracker.gpatracker.tenancy.UserOwnedEntity;

import java.time.Instant;

/**
 * Shared shape for a snapshot of what was last pushed to an external calendar.
 *
 * <p>The owner comes from {@link UserOwnedEntity}, so an export is stamped on write and filtered on
 * read like any other owned row. It used to declare its own {@code studentId}, which sat outside the
 * tenant contract entirely.
 *
 * <p>Being a {@code @MappedSuperclass} it cannot carry the {@code @Filter} for its subclasses —
 * Hibernate binds filters from the concrete {@code @Entity} only. {@link GoogleCalendarExport}
 * declares its own.
 */
@MappedSuperclass
public abstract class AbstractCalendarExport extends UserOwnedEntity {

    @Column(name = "exported_at")
    protected Instant exportedAt;

    public Instant getExportedAt() {
        return exportedAt;
    }

    public void setExportedAt(Instant exportedAt) {
        this.exportedAt = exportedAt;
    }
}
