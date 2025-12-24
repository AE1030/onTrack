package org.tracker.gpatracker.mailing;
import org.springframework.web.util.UriComponentsBuilder;
import org.tracker.gpatracker.security.model.Users;

public class AccountVerificationEmailContext extends AbstractEmailContext {
    private String token;

    @Override
    public <T> void init(T context) {
        Users user = (Users) context;

        put("username", user.getUsername());
        setTemplateLocation("mailing/email-verification");
        setSubject("Complete Your Registration");
        setFrom("ahmedelmanufi@gmail.com");//change this to another email address
        setTo(user.getEmail());
    }

    public void setToken(String token) {
        this.token = token;
        put("token", token);
    }

    public void buildVerificationUrl(final String baseURL, final String token) {
        final String url = UriComponentsBuilder.fromHttpUrl(baseURL)
                .path("/verify").queryParam("token", token).toUriString();
        put("verificationURL", url);
    }

}
