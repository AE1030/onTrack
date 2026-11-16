package org.tracker.gpatracker.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.security.exception.InvalidTokenException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns every exception a controller throws into a short JSON body with no stack trace, class name
 * or library text in it. The real exception is logged.
 *
 * <p>Errors raised before a controller runs (in a filter, or for a URL nothing maps) go to Spring's
 * /error endpoint instead, which application.properties stops from including a trace, message or
 * exception type. Tomcat's own error page is silenced in {@code ErrorPageConfig}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(Map.of("error", message, "status", 400));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(Map.of("error", message, "status", 400));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request body", "status", 400));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        String message = ex.getReason() != null ? ex.getReason() : "Request failed";
        return ResponseEntity.status(status).body(Map.of("error", message, "status", status));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Access denied", "status", 403));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentication required", "status", 401));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "File size exceeds the maximum allowed limit", "status", 413));
    }

    /** Answers for the rule that was actually broken; see {@link DatabaseConflict}. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        DatabaseConflict conflict = DatabaseConflict.from(ex);
        logger.warn("Database rule broken: constraint={} sqlState={}", conflict.constraint(), conflict.sqlState());
        int status = conflict.status().value();
        return ResponseEntity.status(status).body(Map.of("error", conflict.message(), "status", status));
    }

    /**
     * Only onTrack's own throws carry a message meant for the student. One from the JDK or a
     * library ("No enum constant ...") was passed straight through before.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        String message = UserFacingMessage.of(ex, "Invalid request");
        if (!message.equals(ex.getMessage())) {
            logger.warn("Rejected request: {}", ex.toString());
        }
        return ResponseEntity.badRequest().body(Map.of("error", message, "status", 400));
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidToken(InvalidTokenException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", UserFacingMessage.of(ex, "Invalid or expired token"), "status", 400));
    }

    /** A path or query value of the wrong type, such as text where an id goes. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request", "status", 400));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        // Spring's own request errors (unknown route, wrong method, missing parameter, unsupported
        // content type) carry their real status. Without this they all landed here as a 500 and
        // were logged as server faults.
        if (ex instanceof ErrorResponse errorResponse) {
            int status = errorResponse.getStatusCode().value();
            if (status < 500) {
                logger.debug("Request rejected with {}: {}", status, ex.toString());
                return ResponseEntity.status(status).body(Map.of("error", clientErrorText(status), "status", status));
            }
        }
        logger.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error", "status", 500));
    }

    private static String clientErrorText(int status) {
        return switch (status) {
            case 404 -> "Not found";
            case 405 -> "Method not allowed";
            case 406 -> "Not acceptable";
            case 413 -> "Request too large";
            case 415 -> "Unsupported content type";
            default -> "Invalid request";
        };
    }
}
