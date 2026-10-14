package org.tracker.gpatracker.calendar.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwnedEntity;

import java.time.Instant;
import java.util.List;

/**
 * One append-only snapshot of what was pushed to one of a student's external calendars.
 *
 * <p>The sync diffs the newest snapshot against the current projection to work out what to create
 * and delete, which is what the descending {@code exported_at} index serves.
 *
 * <p>Snapshots are keyed by provider <em>and</em> account email, not provider alone. A student who
 * reconnects Google with a different address gets an empty calendar; diffing against the old
 * address's snapshot would conclude everything was already there and push nothing. Keying on the
 * email also lets a student switch back to the old address and resume where it left off.
 *
 * <p>{@code events} maps straight to jsonb with Hibernate's own format mapper. No converter is
 * needed: the nested event type holds nothing encrypted and nothing whose key names are shared with
 * another language.
 */
@Entity
@Table(
        name = "calendar_export",
        indexes = @Index(
                name = "idx_calendar_export_owner_account_time",
                columnList = "student_id, provider, account_email, exported_at desc"))
@Filter(name = OwnerFilter.NAME)
public class CalendarExport extends UserOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private CalendarProvider provider;

    /** The account the events were pushed to. Null when the provider never told us its email. */
    @Column(name = "account_email")
    private String accountEmail;

    @Column(name = "exported_at")
    private Instant exportedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "events")
    private List<ExportedEvent> events;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public CalendarProvider getProvider() {
        return provider;
    }

    public void setProvider(CalendarProvider provider) {
        this.provider = provider;
    }

    public String getAccountEmail() {
        return accountEmail;
    }

    public void setAccountEmail(String accountEmail) {
        this.accountEmail = accountEmail;
    }

    public Instant getExportedAt() {
        return exportedAt;
    }

    public void setExportedAt(Instant exportedAt) {
        this.exportedAt = exportedAt;
    }

    public List<ExportedEvent> getEvents() {
        return events;
    }

    public void setEvents(List<ExportedEvent> events) {
        this.events = events;
    }

    /** Stored inside the {@code events} jsonb payload, not as a table of its own. */
    public static class ExportedEvent extends AbstractCalendarEvent {

        /** The provider's id for the event, which is the only handle we have to delete it later. */
        private String externalEventId;

        public String getExternalEventId() {
            return externalEventId;
        }

        public void setExternalEventId(String externalEventId) {
            this.externalEventId = externalEventId;
        }
    }
}
