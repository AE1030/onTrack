package org.tracker.gpatracker.d2l.exceptions;

/**
 * Thrown when a user's long-lived D2L session is dead (browser-free refresh returned 401/403) or no
 * session exists. Surfaced to the client as HTTP 409 so the app re-launches the WebView login.
 */
public class D2LSessionExpiredException extends RuntimeException {

    public D2LSessionExpiredException(String message) {
        super(message);
    }
}
