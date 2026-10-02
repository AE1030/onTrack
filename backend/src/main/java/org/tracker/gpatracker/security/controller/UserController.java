package org.tracker.gpatracker.security.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.tracker.gpatracker.annotation.TrackExecution;
import org.tracker.gpatracker.security.dto.AuthTokens;
import org.tracker.gpatracker.security.dto.LoginRequest;
import org.tracker.gpatracker.security.dto.RefreshRequest;
import org.tracker.gpatracker.security.dto.RegisterRequest;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.service.UserService;

import java.util.Map;

@RestController
public class UserController {

    private static final Logger logger = LoggerFactory.getLogger(UserController.class);
    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }


    @PostMapping("/register")
    @TrackExecution
    public ResponseEntity<String> register(@Valid @RequestBody RegisterRequest registerDTO) {
        logger.info("POST /register — email domain: {}", registerDTO.getEmail().substring(registerDTO.getEmail().indexOf('@')));
        Users user = new Users();
        user.setEmail(registerDTO.getEmail());
        user.setUsername(registerDTO.getUsername());
        user.setPassword(registerDTO.getPassword());
        service.register(user);
        logger.info("POST /register — success");
        return ResponseEntity.status(HttpStatus.CREATED).body("Registration successful. Please verify your email.");
    }
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest loginDTO) {
        logger.info("POST /login — attempt");
        Users user = new Users();
        user.setEmail(loginDTO.getEmail());
        user.setPassword(loginDTO.getPassword());
        String emailDomain = loginDTO.getEmail().substring(loginDTO.getEmail().indexOf('@'));
        try {
            AuthTokens tokens = service.verifyLogin(user);
            logger.info("POST /login — success");
            return ResponseEntity.ok(tokens);
        } catch (DisabledException e) {
            logger.warn("POST /login — email not verified for: {}", emailDomain);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Email not verified");
        } catch (AuthenticationException e) {
            logger.warn("POST /login — failed for email domain: {}", emailDomain);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid email or password");
        }
    }

    /** Trades a refresh token for a fresh access token and a rotated refresh token. */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest request) {
        return service.refresh(request.getRefreshToken())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Your session has ended. Please sign in again.", "status", 401)));
    }

    /** Ends the session the refresh token belongs to. Always succeeds, even for an unknown token. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        service.logout(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }
}
