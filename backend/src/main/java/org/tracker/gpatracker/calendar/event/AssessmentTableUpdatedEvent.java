package org.tracker.gpatracker.calendar.event;

import org.springframework.context.ApplicationEvent;

public class AssessmentTableUpdatedEvent extends ApplicationEvent {

    private final Long studentId;

    public AssessmentTableUpdatedEvent(Object source, Long studentId) {
        super(source);
        this.studentId = studentId;
    }

    public Long getStudentId() {
        return studentId;
    }
}
