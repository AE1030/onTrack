package org.tracker.gpatracker.calendar.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;

import java.util.List;

@Document(collection = "googleCalendarExports")
@CompoundIndex(name = "idx_calendar_export_student_time", def = "{'studentId': 1, 'exportedAt': -1}")
public class GoogleCalendarExport extends AbstractCalendarExport {

    @Id
    private String id;

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
