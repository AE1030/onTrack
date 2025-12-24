package org.tracker.gpatracker.security.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.tracker.gpatracker.security.exception.InvalidTokenException;
import org.tracker.gpatracker.security.service.UserService;

import java.util.Map;

@Controller
public class EmailVerificationController {

    @Autowired
    private UserService userService;

    @GetMapping("/verify")
    public ResponseEntity<?> verifyUser(@RequestParam("token") String token) {
        if (!StringUtils.hasText(token)) {
            return ResponseEntity.badRequest().body("Verification token is required");
        }
        try {
            userService.verifyUser(token);
            return ResponseEntity.ok("Email Verified Successfully");
        } catch (InvalidTokenException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
    }

    @PostMapping("/verify/resend")
    public ResponseEntity<?> resendVerification(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (!StringUtils.hasText(email)) {
            return ResponseEntity.badRequest().body("Email is required");
        }
        try {
            userService.resendVerificationEmail(email);
            return ResponseEntity.ok("Verification email sent");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
    }
}
