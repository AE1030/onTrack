package org.tracker.gpatracker.calendar.model;

import java.time.Instant;

public abstract class AbstractCalendarExport {

    protected Long studentId;
    protected Instant exportedAt;

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public Instant getExportedAt() {
        return exportedAt;
    }

    public void setExportedAt(Instant exportedAt) {
        this.exportedAt = exportedAt;
    }
}
