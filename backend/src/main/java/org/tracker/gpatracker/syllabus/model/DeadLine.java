package org.tracker.gpatracker.syllabus.model;

public record DeadLine(
        String courseCode,
        String assessmentName,
        String dueDate,
        double weight
) {}