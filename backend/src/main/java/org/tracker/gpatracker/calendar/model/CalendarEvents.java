package org.tracker.gpatracker.calendar.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.tracker.gpatracker.tenancy.mongo.UserOwnedDocument;

import java.time.Instant;
import java.util.List;

@Document(collection = "calendarEvents")
@CompoundIndex(name = "idx_calendar_events_student", def = "{'studentId': 1}")
public class CalendarEvents extends UserOwnedDocument {

    @Id
    private String id;

    private List<InternalCalendarEvent> events;

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
