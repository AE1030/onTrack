package org.tracker.gpatracker.security.exception;

/** Thrown when a verification email is requested for an account that is already verified. */
public class EmailAlreadyVerifiedException extends IllegalStateException {
    public EmailAlreadyVerifiedException(String message) {
        super(message);
    }
}
