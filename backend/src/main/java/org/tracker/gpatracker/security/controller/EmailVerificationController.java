package org.tracker.gpatracker.security.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.tracker.gpatracker.security.exception.InvalidTokenException;
import org.tracker.gpatracker.security.service.UserService;

import java.util.Map;

@Controller
public class EmailVerificationController {

    private static final Logger logger = LoggerFactory.getLogger(EmailVerificationController.class);

    @Value("${site.base.url.https}")
    private String baseUrl;

    private final UserService userService;

    public EmailVerificationController(UserService userService) {
        this.userService = userService;
    }

    @ResponseBody
    @GetMapping("/verify")
    public ResponseEntity<String> verifyUser(@RequestParam("token") String token) throws InvalidTokenException {
        if (!StringUtils.hasText(token)) {
            return ResponseEntity.badRequest().body("Verification token is required");
        }
        userService.verifyUser(token);
        return ResponseEntity.ok("Email Verified Successfully");
    }

    @ResponseBody
    @PostMapping("/verify/resend")
    public ResponseEntity<String> resendVerification(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (!StringUtils.hasText(email)) {
            return ResponseEntity.badRequest().body("Email is required");
        }
        try {
            userService.resendVerificationEmail(email);
            return ResponseEntity.ok("Verification email sent");
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (!StringUtils.hasText(email)) {
            return ResponseEntity.badRequest().body("Email is required");
        }
        try {
            userService.forgotPassword(email);
            return ResponseEntity.ok("If an account exists with that email, a reset link has been sent.");
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(e.getMessage());
        }
    }

    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam("token") String token, org.springframework.ui.Model model) {
        model.addAttribute("token", token);
        model.addAttribute("baseUrl", baseUrl);
        return "mailing/reset-password-form";
    }

    @ResponseBody
    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@RequestBody Map<String, String> body) throws InvalidTokenException {
        logger.info("POST /reset-password received — token present: {}, newPassword present: {}",
                body.containsKey("token") && StringUtils.hasText(body.get("token")),
                body.containsKey("newPassword") && StringUtils.hasText(body.get("newPassword")));
        String token = body.get("token");
        String newPassword = body.get("newPassword");
        if (!StringUtils.hasText(token) || !StringUtils.hasText(newPassword)) {
            logger.warn("POST /reset-password — missing token or password");
            return ResponseEntity.badRequest().body("Token and new password are required");
        }
        try {
            userService.resetPassword(token, newPassword);
            logger.info("POST /reset-password — success");
            return ResponseEntity.ok("Password reset successfully");
        } catch (Exception e) {
            logger.error("POST /reset-password — failed: {}", e.getMessage(), e);
            throw e;
        }
    }
}
