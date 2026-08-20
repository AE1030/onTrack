package org.tracker.gpatracker.calendar.model;

import org.tracker.gpatracker.tenancy.mongo.UserOwnedDocument;

import java.time.Instant;

/**
 * The owner now lives on {@link UserOwnedDocument}, so an export is stamped and asserted like any
 * other owned document. It used to declare its own {@code studentId}, which sat outside the tenant
 * contract entirely. The stored field name is unchanged.
 */
public abstract class AbstractCalendarExport extends UserOwnedDocument {

    protected Instant exportedAt;

    public Instant getExportedAt() {
        return exportedAt;
    }

    public void setExportedAt(Instant exportedAt) {
        this.exportedAt = exportedAt;
    }
}
