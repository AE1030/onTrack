package org.tracker.gpatracker.accounts.event;

import org.springframework.context.ApplicationEvent;

public class GpaRecalculationEvent extends ApplicationEvent {

    private final Long studentId;

    public GpaRecalculationEvent(Object source, Long studentId) {
        super(source);
        this.studentId = studentId;
    }

    public Long getStudentId() {
        return studentId;
    }
}
