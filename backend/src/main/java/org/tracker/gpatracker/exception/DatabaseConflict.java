package org.tracker.gpatracker.exception;

import org.springframework.http.HttpStatus;

import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out what a database integrity failure means for the student who caused it.
 *
 * <p>Every such failure used to be answered "Email already in use", whichever rule was broken: a
 * double-tapped save colliding on a one-row-per-student table told the student their email was
 * taken. This reads the Postgres error code and the constraint name off the cause chain and answers
 * for that rule.
 *
 * <p>Only the rules a student can trip on purpose get their own sentence. The rest are
 * one-row-per-student tables, where a collision means the same request landed twice at once, and
 * a refresh shows the row that won.
 */
public record DatabaseConflict(HttpStatus status, String message, String constraint, String sqlState) {

    static final String UNIQUE_VIOLATION = "23505";
    static final String FOREIGN_KEY_VIOLATION = "23503";

    static final String GENERIC_CONFLICT =
            "That could not be saved because it clashes with something already saved. Refresh and try again.";
    static final String INVALID = "Some of that information is missing or not valid.";

    /** Unique rules a student can break deliberately, by constraint name. */
    private static final Map<String, String> KNOWN = Map.of(
            "users_email_key", "An account with that email already exists.",
            "users_username_key", "That username is taken.",
            "uk_leaderboard_profile_handle", "That handle is taken.");

    /** Postgres names the constraint in its message: ... violates unique constraint "users_email_key". */
    private static final Pattern NAMED = Pattern.compile("constraint \"([A-Za-z0-9_]+)\"");

    public static DatabaseConflict from(Throwable ex) {
        String sqlState = null;
        String constraint = null;
        for (Throwable t = ex; t != null && t.getCause() != t; t = t.getCause()) {
            if (sqlState == null && t instanceof SQLException sql) {
                sqlState = sql.getSQLState();
            }
            if (constraint == null && t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                constraint = cve.getConstraintName();
            }
            if (constraint == null && t.getMessage() != null) {
                Matcher m = NAMED.matcher(t.getMessage());
                if (m.find()) {
                    constraint = m.group(1);
                }
            }
        }
        if (constraint != null) {
            constraint = constraint.toLowerCase();
        }

        if (UNIQUE_VIOLATION.equals(sqlState) || (sqlState == null && constraint != null && KNOWN.containsKey(constraint))) {
            String message = constraint == null ? GENERIC_CONFLICT : KNOWN.getOrDefault(constraint, GENERIC_CONFLICT);
            return new DatabaseConflict(HttpStatus.CONFLICT, message, constraint, sqlState);
        }
        if (FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            // Something this row points at was removed in the meantime, or is still pointed at.
            return new DatabaseConflict(HttpStatus.CONFLICT, GENERIC_CONFLICT, constraint, sqlState);
        }
        // Not-null and check rules: a value the request should have been refused for earlier.
        return new DatabaseConflict(HttpStatus.BAD_REQUEST, INVALID, constraint, sqlState);
    }
}
