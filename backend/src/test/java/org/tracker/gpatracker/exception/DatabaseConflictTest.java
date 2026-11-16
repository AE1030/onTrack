package org.tracker.gpatracker.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConflictTest {

    /** The shape Spring hands the handler: Spring wraps Hibernate, which wraps the driver's error. */
    private static DataIntegrityViolationException violation(String sqlState, String constraint) {
        String detail = "ERROR: violates constraint \"" + constraint + "\"";
        SQLException driver = new SQLException(detail, sqlState);
        org.hibernate.exception.ConstraintViolationException hibernate =
                new org.hibernate.exception.ConstraintViolationException("could not execute statement", driver, constraint);
        return new DataIntegrityViolationException("could not execute statement", hibernate);
    }

    @Test
    @DisplayName("a duplicate email says so")
    void duplicateEmail() {
        DatabaseConflict c = DatabaseConflict.from(violation("23505", "users_email_key"));
        assertThat(c.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(c.message()).isEqualTo("An account with that email already exists.");
    }

    @Test
    @DisplayName("a taken leaderboard handle says so, not that the email is in use")
    void takenHandle() {
        assertThat(DatabaseConflict.from(violation("23505", "uk_leaderboard_profile_handle")).message())
                .isEqualTo("That handle is taken.");
    }

    @Test
    @DisplayName("a double-submitted save on a one-row-per-student table gets the refresh message")
    void otherUniqueRule() {
        DatabaseConflict c = DatabaseConflict.from(violation("23505", "uk_syllabus_upload_quota_student"));
        assertThat(c.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(c.message()).isEqualTo(DatabaseConflict.GENERIC_CONFLICT).doesNotContain("Email");
    }

    @Test
    @DisplayName("a not-null failure is a bad request, not a conflict")
    void notNull() {
        DatabaseConflict c = DatabaseConflict.from(violation("23502", "users_email_not_null"));
        assertThat(c.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(c.message()).isEqualTo(DatabaseConflict.INVALID);
    }

    @Test
    @DisplayName("with nothing to go on, it never claims a specific cause")
    void unknown() {
        DatabaseConflict c = DatabaseConflict.from(new DataIntegrityViolationException("boom"));
        assertThat(c.message()).doesNotContain("Email").doesNotContain("handle");
    }
}
