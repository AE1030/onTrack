package org.tracker.gpatracker.security.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.tracker.gpatracker.annotation.TrackExecution;
import org.tracker.gpatracker.security.dto.LoginRequest;
import org.tracker.gpatracker.security.dto.RegisterRequest;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.service.UserService;

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
    public ResponseEntity<String> login(@Valid @RequestBody LoginRequest loginDTO) {
        logger.info("POST /login — attempt");
        Users user = new Users();
        user.setEmail(loginDTO.getEmail());
        user.setPassword(loginDTO.getPassword());
        String result = service.verifyLogin(user);
        if ("Login Failed".equals(result)) {
            logger.warn("POST /login — failed for email domain: {}", loginDTO.getEmail().substring(loginDTO.getEmail().indexOf('@')));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid email or password");
        }
        logger.info("POST /login — success");
        return ResponseEntity.ok(result);
    }
}
