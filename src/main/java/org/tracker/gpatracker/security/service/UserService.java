package org.tracker.gpatracker.security.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tracker.gpatracker.security.exception.InvalidTokenException;
import org.tracker.gpatracker.mailing.AccountVerificationEmailContext;
import org.tracker.gpatracker.mailing.EmailService;
import org.tracker.gpatracker.security.model.Role;
import org.tracker.gpatracker.security.model.SecureToken;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.RolesRepo;
import org.tracker.gpatracker.security.repository.SecureTokenRepository;
import org.tracker.gpatracker.security.repository.UserRepo;

import java.time.LocalDateTime;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    @Value("${site.base.url.https}")
    private String baseUrl;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private EmailService emailService;

    @Autowired
    private SecureTokenRepository secureTokenRepository;

    @Autowired
    private RolesRepo rolesRepo;

    @Autowired
    DefaultSecureTokenService secureTokenService;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    AuthenticationManager authManager;

    @Autowired
    JWTService jwtService;

    @Transactional
    public Users register(Users user) {

        if (userRepo.existsByEmail(user.getEmail())) {
            throw new IllegalArgumentException("Email already in use");
        }

        if (!user.getEmail().endsWith("@mcmaster.ca")) {
            throw new IllegalArgumentException("Please use a valid McMaster email");
        }

        Role defaultRole = rolesRepo.findById(2L)
                .orElseThrow(() -> new RuntimeException("Role not found"));
        user.getRoles().add(defaultRole);

        user.setEmailVerified(false);
        user.setAccountEnabled(true);
        user.setAccountLocked(false);
        user.setLastLoginAt(LocalDateTime.now());
        user.setPassword(passwordEncoder.encode(user.getPassword()));

        Users savedUser = userRepo.save(user);
        sendRegisterationVerificationEmail(savedUser);
        return savedUser;
    }

    public void sendRegisterationVerificationEmail(Users user) {
        // Implementation for sending verification email
        SecureToken secureToken = secureTokenService.saveAndFlush(user);//creates and saves token
        AccountVerificationEmailContext emailContext = new AccountVerificationEmailContext();
        emailContext.init(user);
        emailContext.setToken(secureToken.getToken());
        emailContext.buildVerificationUrl(baseUrl, secureToken.getToken());
        try{
            emailService.sendMail(emailContext);
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }
    public boolean verifyUser(String token) throws InvalidTokenException {
        if (StringUtils.isBlank(token)) {
            throw new InvalidTokenException("Token is not valid");
        }

        String decoded = URLDecoder.decode(token, StandardCharsets.UTF_8);
        String cleanedToken = decoded.trim();
        SecureToken secureToken = secureTokenService.findByToken(cleanedToken);
        if (secureToken == null) {
            log.warn("Verification failed: token not found {}, tokens in repo: {}", cleanedToken, secureTokenRepository.findAll()
                    .stream()
                    .map(SecureToken::getToken)
                    .toList());
            throw new InvalidTokenException("Token is not valid");
        }
        if (secureToken.getExpiredAt() == null || secureToken.isExpired()) {
            log.warn("Verification failed: token expired at {}", secureToken.getExpiredAt());
            throw new InvalidTokenException("Token is not valid");
        }

        Users user = userRepo.findById(secureToken.getUser().getId()).orElse(null);
        if (Objects.isNull(user)) {
            log.warn("Verification failed: user not found for token {}", cleanedToken);
            return false;
        }

        user.setEmailVerified(true);
        userRepo.save(user);
        //Create a student profile for the user
        secureTokenService.removeToken(cleanedToken);
        return true;
    }

    @Transactional
    public void resendVerificationEmail(String email) {
        if (StringUtils.isBlank(email)) {
            throw new IllegalArgumentException("Email is required");
        }
        Users user = userRepo.findByEmail(email);
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }
        if (user.isEmailVerified()) {
            throw new IllegalStateException("User already verified");
        }
        secureTokenRepository.deleteByUser(user);
        sendRegisterationVerificationEmail(user);
    }

    public String verifyLogin(Users user) {
        //If the user is not authenticated the authManager.authenticate returns an unchecked error
        //unchecked errors don't need to be handled
        //but we neeed to catch and handle the error if we want to output login failed to the user
        try {
            Authentication auth =
                    authManager.authenticate(
                            new UsernamePasswordAuthenticationToken(
                                    user.getEmail(),
                                    user.getPassword()
                            )
                    );
            return jwtService.generateToken(user.getUsername());

        } catch (AuthenticationException e) {
            return "Login Failed";

        }
    }
}
