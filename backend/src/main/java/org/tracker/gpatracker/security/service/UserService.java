package org.tracker.gpatracker.security.service;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.security.dto.AuthTokens;
import org.tracker.gpatracker.security.exception.InvalidTokenException;
import org.tracker.gpatracker.mailing.AccountVerificationEmailContext;
import org.tracker.gpatracker.mailing.PasswordResetEmailContext;
import org.tracker.gpatracker.mailing.EmailService;
import org.tracker.gpatracker.security.model.Role;
import org.tracker.gpatracker.security.model.SecureToken;
import org.tracker.gpatracker.security.model.TokenType;
import org.tracker.gpatracker.security.model.UserPrincipal;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.RolesRepo;
import org.tracker.gpatracker.security.repository.SecureTokenRepository;
import org.tracker.gpatracker.security.repository.UserRepo;

import java.time.LocalDateTime;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");

    @Value("${site.base.url.https}")
    private String baseUrl;

    private final UserRepo userRepo;
    private final StudentService studentService;
    private final EmailService emailService;
    private final SecureTokenRepository secureTokenRepository;
    private final RolesRepo rolesRepo;
    private final DefaultSecureTokenService secureTokenService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthenticationManager authManager;
    private final JWTService jwtService;
    private final RefreshTokenService refreshTokenService;

    public UserService(UserRepo userRepo, StudentService studentService, EmailService emailService,
                       SecureTokenRepository secureTokenRepository, RolesRepo rolesRepo,
                       DefaultSecureTokenService secureTokenService, BCryptPasswordEncoder passwordEncoder,
                       AuthenticationManager authManager, JWTService jwtService,
                       RefreshTokenService refreshTokenService) {
        this.userRepo = userRepo;
        this.studentService = studentService;
        this.emailService = emailService;
        this.secureTokenRepository = secureTokenRepository;
        this.rolesRepo = rolesRepo;
        this.secureTokenService = secureTokenService;
        this.passwordEncoder = passwordEncoder;
        this.authManager = authManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public Users register(Users user) {

        if (userRepo.existsByEmail(user.getEmail())) {
            throw new IllegalArgumentException("Email already in use");
        }

        if (!user.getEmail().endsWith("@mcmaster.ca")) {
            throw new IllegalArgumentException("Please use a valid McMaster email");
        }

        if (!PASSWORD_PATTERN.matcher(user.getPassword()).matches()) {
            throw new IllegalArgumentException("Password must be at least 8 characters with one uppercase, one lowercase, and one number");
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
        SecureToken secureToken = secureTokenService.saveAndFlush(user, TokenType.VERIFICATION);
        AccountVerificationEmailContext emailContext = new AccountVerificationEmailContext();
        emailContext.init(user);
        emailContext.setToken(secureToken.getToken());
        emailContext.buildVerificationUrl(baseUrl, secureToken.getToken());
        try{
            emailService.sendMail(emailContext);
        }
        catch (Exception e){
            logger.error("Failed to send email", e);
        }
    }
    @Transactional
    public boolean verifyUser(String token) throws InvalidTokenException {
        if (StringUtils.isBlank(token)) {
            throw new InvalidTokenException("Token is not valid");
        }

        String decoded = URLDecoder.decode(token, StandardCharsets.UTF_8);
        String cleanedToken = decoded.trim();
        SecureToken secureToken = secureTokenService.findByToken(cleanedToken);
        if (secureToken == null || secureToken.getTokenType() != TokenType.VERIFICATION) {
            throw new InvalidTokenException("Token is not valid");
        }
        if (secureToken.getExpiredAt() == null || secureToken.isExpired()) {
            throw new InvalidTokenException("Token is not valid");
        }

        Users user = userRepo.findById(secureToken.getUser().getId()).orElse(null);
        if (Objects.isNull(user)) {
            return false;
        }

        // Remove token FIRST to prevent double-spend (second concurrent request will fail at findByToken)
        secureTokenService.removeToken(cleanedToken);
        user.setEmailVerified(true);
        userRepo.save(user);
        studentService.createStudentAccount(user);
        return true;
    }

    private static final long RESEND_COOLDOWN_SECONDS = 60;

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

        // Reuse existing token if it's still valid; enforce cooldown to prevent spam
        SecureToken existingToken = secureTokenRepository.findByUserAndTokenType(user, TokenType.VERIFICATION);
        if (existingToken != null && !existingToken.isExpired()) {
            LocalDateTime createdAt = existingToken.getExpiredAt().minusSeconds(900);
            if (createdAt.plusSeconds(RESEND_COOLDOWN_SECONDS).isAfter(LocalDateTime.now())) {
                throw new IllegalStateException("Please wait before requesting another email");
            }
            // Resend the same token so all emails in inbox use the same valid link
            AccountVerificationEmailContext emailContext = new AccountVerificationEmailContext();
            emailContext.init(user);
            emailContext.setToken(existingToken.getToken());
            emailContext.buildVerificationUrl(baseUrl, existingToken.getToken());
            try {
                emailService.sendMail(emailContext);
            } catch (Exception e) {
                logger.error("Failed to resend verification email", e);
            }
            return;
        }

        // Token expired or doesn't exist — create a new one
        secureTokenRepository.deleteByUserAndTokenType(user, TokenType.VERIFICATION);
        sendRegisterationVerificationEmail(user);
    }

    @Transactional
    public void forgotPassword(String email) {
        if (StringUtils.isBlank(email)) {
            throw new IllegalArgumentException("Email is required");
        }
        Users user = userRepo.findByEmail(email);
        if (user == null) {
            // Don't reveal whether the email exists
            return;
        }

        // Reuse existing token if still valid; enforce cooldown to prevent spam
        SecureToken existingToken = secureTokenRepository.findByUserAndTokenType(user, TokenType.PASSWORD_RESET);
        if (existingToken != null && !existingToken.isExpired()) {
            LocalDateTime createdAt = existingToken.getExpiredAt().minusSeconds(900);
            if (createdAt.plusSeconds(RESEND_COOLDOWN_SECONDS).isAfter(LocalDateTime.now())) {
                throw new IllegalStateException("Please wait before requesting another email");
            }
            PasswordResetEmailContext emailContext = new PasswordResetEmailContext();
            emailContext.init(user);
            emailContext.setToken(existingToken.getToken());
            emailContext.buildResetUrl(baseUrl, existingToken.getToken());
            try {
                emailService.sendMail(emailContext);
            } catch (Exception e) {
                logger.error("Failed to resend password reset email", e);
            }
            return;
        }

        // Token expired or doesn't exist — create a new one
        secureTokenRepository.deleteByUserAndTokenType(user, TokenType.PASSWORD_RESET);
        SecureToken secureToken = secureTokenService.saveAndFlush(user, TokenType.PASSWORD_RESET);
        PasswordResetEmailContext emailContext = new PasswordResetEmailContext();
        emailContext.init(user);
        emailContext.setToken(secureToken.getToken());
        emailContext.buildResetUrl(baseUrl, secureToken.getToken());
        try {
            emailService.sendMail(emailContext);
        } catch (Exception e) {
            logger.error("Failed to send password reset email", e);
        }
    }

    @Transactional
    public void resetPassword(String token, String newPassword) throws InvalidTokenException {
        logger.info("resetPassword — start");
        if (StringUtils.isBlank(token)) {
            logger.warn("resetPassword — token is blank");
            throw new InvalidTokenException("Token is not valid");
        }

        String decoded = java.net.URLDecoder.decode(token, java.nio.charset.StandardCharsets.UTF_8).trim();
        logger.info("resetPassword — decoded token length: {}", decoded.length());
        SecureToken secureToken = secureTokenService.findByToken(decoded);
        if (secureToken == null || secureToken.getTokenType() != TokenType.PASSWORD_RESET) {
            logger.warn("resetPassword — token not found or wrong type (found: {})", secureToken != null ? secureToken.getTokenType() : "null");
            throw new InvalidTokenException("Token is not valid");
        }
        logger.info("resetPassword — token found, type: {}, expiredAt: {}, isExpired: {}",
                secureToken.getTokenType(), secureToken.getExpiredAt(), secureToken.isExpired());
        if (secureToken.getExpiredAt() == null || secureToken.isExpired()) {
            logger.warn("resetPassword — token has expired");
            throw new InvalidTokenException("Token has expired");
        }

        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            logger.warn("resetPassword — password does not meet requirements");
            throw new IllegalArgumentException("Password must be at least 8 characters with one uppercase, one lowercase, and one number");
        }

        Users user = userRepo.findById(secureToken.getUser().getId()).orElse(null);
        if (user == null) {
            logger.warn("resetPassword — user not found for token");
            throw new InvalidTokenException("Token is not valid");
        }

        logger.info("resetPassword — resetting password for user id: {}", user.getId());
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepo.save(user);
        secureTokenService.removeToken(decoded);
        // A reset is how a user recovers a compromised account, so it must end every existing session.
        refreshTokenService.revokeAll(user);
        logger.info("resetPassword — complete");
    }

    /**
     * Authenticates the credentials and starts a session.
     *
     * @throws DisabledException       when the email is not verified
     * @throws AuthenticationException when the credentials are wrong
     */
    @Transactional
    public AuthTokens verifyLogin(Users user) {
        Authentication auth =
                authManager.authenticate(
                        new UsernamePasswordAuthenticationToken(
                                user.getEmail(),
                                user.getPassword()
                        )
                );
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        Long userId = principal.getId();
        String accessToken = issueAccessToken(principal.getUsername(), userId);
        String refreshToken = refreshTokenService.issue(userRepo.getReferenceById(userId));
        return new AuthTokens(accessToken, refreshToken);
    }

    /**
     * Trades a refresh token for a new access token and a new refresh token. Empty when the refresh
     * token is not usable or the account can no longer sign in; the caller must send the user back
     * to login.
     */
    @Transactional
    public Optional<AuthTokens> refresh(String rawRefreshToken) {
        return refreshTokenService.rotate(rawRefreshToken).flatMap(issued -> {
            Users account = issued.user();
            if (!account.isEmailVerified() || !account.isAccountEnabled() || account.isAccountLocked()) {
                refreshTokenService.revokeAll(account);
                return Optional.empty();
            }
            String accessToken = issueAccessToken(account.getEmail(), account.getId());
            return Optional.of(new AuthTokens(accessToken, issued.rawToken()));
        });
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    private String issueAccessToken(String email, Long userId) {
        // Resolved at issue time and carried in the signed payload for the token's lifetime.
        // Login requires a verified email and verification creates the Student row, so this
        // is populated in practice; a null surfaces later as an explicit "no student id".
        Long studentId = studentService.findStudentIdByUserId(userId);
        return jwtService.generateToken(email, userId, studentId);
    }
}
