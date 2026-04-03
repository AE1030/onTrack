package org.tracker.gpatracker.mailing;

import org.springframework.web.util.UriComponentsBuilder;
import org.tracker.gpatracker.security.model.Users;

public class PasswordResetEmailContext extends AbstractEmailContext {

    @Override
    public <T> void init(T context) {
        Users user = (Users) context;

        put("username", user.getUsername());
        setTemplateLocation("mailing/password-reset");
        setSubject("Reset Your Password");
        setFrom("noreply@ontrackmac.ca");
        setTo(user.getEmail());
    }

    public void setToken(String token) {
        put("token", token);
    }

    public void buildResetUrl(final String baseURL, final String token) {
        final String url = UriComponentsBuilder.fromUriString(baseURL)
                .path("/reset-password").queryParam("token", token).toUriString();
        put("resetURL", url);
    }
}