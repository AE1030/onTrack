package org.tracker.gpatracker.calendar.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class GoogleOAuthDiagnostics implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(GoogleOAuthDiagnostics.class);

    private final Environment environment;
    private final GoogleOAuthProperties properties;

    public GoogleOAuthDiagnostics(Environment environment, GoogleOAuthProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String clientId = trimSafe(properties.getClientId());
        String redirectUri = trimSafe(properties.getRedirectUri());
        logger.info("Google OAuth config clientId={} redirectUri={} clientSecret=***",
                clientId, redirectUri);
        String envClientId = trimSafe(environment.getProperty("GOOGLE_OAUTH_CLIENT_ID"));
        if (envClientId != null) {
            logger.info("Google OAuth env GOOGLE_OAUTH_CLIENT_ID is set");
        }
        String envSecret = trimSafe(environment.getProperty("GOOGLE_OAUTH_CLIENT_SECRET"));
        if (envSecret != null) {
            logger.info("Google OAuth env GOOGLE_OAUTH_CLIENT_SECRET is set (length={})", envSecret.length());
        }
        String envRedirect = trimSafe(environment.getProperty("GOOGLE_OAUTH_REDIRECT_URI"));
        if (envRedirect != null) {
            logger.info("Google OAuth env GOOGLE_OAUTH_REDIRECT_URI is set");
        }
    }

    private String trimSafe(String value) {
        return value != null ? value.trim() : null;
    }
}
