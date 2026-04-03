package org.tracker.gpatracker.calendar.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "calendarEvents")
public class CalendarEvents {

    @Id
    private String id;

    private Long studentId;

    private List<InternalCalendarEvent> events;

    private Instant lastUpdatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
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
