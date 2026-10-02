package org.tracker.gpatracker.calendar.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.tracker.gpatracker.tenancy.OwnerFilter;

import java.util.List;

/**
 * One append-only snapshot of what was pushed to a student's Google Calendar.
 *
 * <p>The sync diffs the newest snapshot against the current projection to work out what to create
 * and delete, which is what the descending {@code exported_at} index serves.
 *
 * <p>{@code events} maps straight to jsonb with Hibernate's own format mapper. No converter is
 * needed: the nested event type holds nothing encrypted and nothing whose key names are shared with
 * another language.
 */
@Entity
@Table(
        name = "google_calendar_export",
        indexes = @Index(
                name = "idx_calendar_export_student_time",
                columnList = "student_id, exported_at desc"))
@Filter(name = OwnerFilter.NAME)
public class GoogleCalendarExport extends AbstractCalendarExport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36)
    private String id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "events")
    private List<GoogleCalendarEvent> events;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<GoogleCalendarEvent> getEvents() {
        return events;
    }

    public void setEvents(List<GoogleCalendarEvent> events) {
        this.events = events;
    }

    /** Stored inside the {@code events} jsonb payload, not as a table of its own. */
    public static class GoogleCalendarEvent extends AbstractCalendarEvent {

        private String googleEventId;
        private String googleCalendarId;

        public String getGoogleEventId() {
            return googleEventId;
        }

        public void setGoogleEventId(String googleEventId) {
            this.googleEventId = googleEventId;
        }

        public String getGoogleCalendarId() {
            return googleCalendarId;
        }

        public void setGoogleCalendarId(String googleCalendarId) {
            this.googleCalendarId = googleCalendarId;
        }
    }
}
