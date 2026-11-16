package org.tracker.gpatracker.exception;

/**
 * Decides whether an exception's message is fit to send to a student.
 *
 * <p>A message is passed on only when the exception was created in onTrack's own code, where every
 * {@code throw new IllegalArgumentException("...")} is a sentence written for the student. The same
 * exception types are thrown by the JDK and libraries with developer text ("No enum constant
 * org.tracker...", "Invalid UUID string: ...", a mail server's reply), and those get the caller's
 * fallback instead. The check reads the frame the exception was constructed in, so no throw site
 * has to opt in.
 */
public final class UserFacingMessage {

    private static final String APP_PACKAGE = "org.tracker.gpatracker.";

    private UserFacingMessage() {
    }

    public static String of(Throwable ex, String fallback) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return fallback;
        }
        StackTraceElement[] frames = ex.getStackTrace();
        // An empty trace (the JVM's fast-throw optimisation) means the origin cannot be checked.
        if (frames.length == 0 || !frames[0].getClassName().startsWith(APP_PACKAGE)) {
            return fallback;
        }
        return message;
    }
}
