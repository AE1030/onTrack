package org.tracker.gpatracker.syllabus.exception;

public enum SyllabusErrorType {
    INVALID_SYLLABUS,
    GEMINI_ERROR,
    PARSE_ERROR,
    BACKEND_ERROR,
    /** Never finished, most likely because the server restarted mid-job. Set by StaleSyllabusJobSweeper. */
    TIMED_OUT,
    UNKNOWN_ERROR
}
