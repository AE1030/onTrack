package org.tracker.gpatracker.d2l.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tracker.gpatracker.d2l.dto.D2LSessionRequest;
import org.tracker.gpatracker.d2l.service.BrightspaceClient;
import org.tracker.gpatracker.d2l.service.D2LSessionService;
import org.tracker.gpatracker.security.model.UserPrincipal;

import java.util.Map;

/**
 * D2L Brightspace (McMaster Avenue) integration endpoints. All are JWT-protected; the user is resolved
 * from the authenticated principal. This is a <em>standalone</em> client — it exposes raw D2L data and
 * does not yet feed the GPA pipeline.
 */
@RestController
@RequestMapping("/api/d2l")
public class D2LController {

    private static final Logger logger = LoggerFactory.getLogger(D2LController.class);

    private final D2LSessionService sessionService;
    private final BrightspaceClient brightspaceClient;

    public D2LController(D2LSessionService sessionService, BrightspaceClient brightspaceClient) {
        this.sessionService = sessionService;
        this.brightspaceClient = brightspaceClient;
    }

    /** Stores the cookie jar + XSRF token harvested by the device WebView. */
    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> saveSession(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody D2LSessionRequest request) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        sessionService.save(principal.getId(), request.cookieHeader(), request.xsrfToken());
        logger.info("D2L session connected for user {}", principal.getId());
        return ResponseEntity.ok(Map.of("connected", true));
    }

    /** Whether a (stored) D2L session exists, so the app knows when to prompt a WebView re-login. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of("connected", sessionService.hasSession(principal.getId())));
    }

    /** Proof of auth: raw Valence whoami JSON. */
    @GetMapping(value = "/whoami", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> whoami(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(brightspaceClient.whoami(principal.getId()));
    }

    /** Raw Valence enrollment list for the user (standalone; not mapped into CourseEnrollement). */
    @GetMapping(value = "/courses", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> courses(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(brightspaceClient.myEnrollments(principal.getId()));
    }

    /** Disconnects Avenue by deleting the stored session. */
    @DeleteMapping("/session")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        sessionService.delete(principal.getId());
        return ResponseEntity.noContent().build();
    }
}
