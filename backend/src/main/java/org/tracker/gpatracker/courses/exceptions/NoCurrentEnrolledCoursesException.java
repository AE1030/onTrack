package org.tracker.gpatracker.courses.exceptions;

public class NoCurrentEnrolledCoursesException extends RuntimeException {
    public NoCurrentEnrolledCoursesException(String message) {
        super(message);
    }
}
