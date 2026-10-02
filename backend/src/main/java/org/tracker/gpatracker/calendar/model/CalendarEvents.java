package org.tracker.gpatracker.calendar.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwnedEntity;

import java.time.Instant;
import java.util.List;

/**
 * The upcoming-assessment projection for one student, rebuilt wholesale whenever an assessment table
 * changes.
 *
 * <p>Exactly one row per student, which the unique constraint on {@code student_id} enforces rather
 * than assumes. {@code CalendarEventsRepository.findByOwnerId} returns an {@code Optional}, so a
 * second row would make every read throw instead of quietly returning the newer projection.
 */
@Entity
@Table(
        name = "calendar_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_calendar_events_student",
                columnNames = "student_id"))
@Filter(name = OwnerFilter.NAME)
public class CalendarEvents extends UserOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36)
    private String id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "events")
    private List<InternalCalendarEvent> events;

    @Column(name = "last_updated_at")
    private Instant lastUpdatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<InternalCalendarEvent> getEvents() {
        return events;
    }

    public void setEvents(List<InternalCalendarEvent> events) {
        this.events = events;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(Instant lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    /** Stored inside the {@code events} jsonb payload, not as a table of its own. */
    public static class InternalCalendarEvent extends AbstractCalendarEvent {

        private String description;
        private String location;

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }
    }
}
