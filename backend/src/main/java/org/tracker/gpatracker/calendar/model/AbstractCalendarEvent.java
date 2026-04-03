package org.tracker.gpatracker.calendar.model;

public abstract class AbstractCalendarEvent {

    protected String courseCode;
    protected String assessmentName;
    protected String dueDate;     // YYYY-MM-DD
    protected String startTime;   // HH:MM (optional)
    protected String endTime;     // HH:MM (optional)
    protected boolean allDay;
    protected boolean completed;
    protected String eventKey;    // courseCode|assessmentName|dueDate

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getAssessmentName() {
        return assessmentName;
    }

    public void setAssessmentName(String assessmentName) {
        this.assessmentName = assessmentName;
    }

    public String getDueDate() {
        return dueDate;
    }

    public void setDueDate(String dueDate) {
        this.dueDate = dueDate;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public boolean isAllDay() {
        return allDay;
    }

    public void setAllDay(boolean allDay) {
        this.allDay = allDay;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public static String buildEventKey(String courseCode, String assessmentName, String dueDate) {
        return courseCode + "|" + assessmentName + "|" + dueDate;
    }
}
