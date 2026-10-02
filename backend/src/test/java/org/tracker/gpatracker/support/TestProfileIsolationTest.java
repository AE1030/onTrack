package org.tracker.gpatracker.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the test profile is hermetic: every value the application reads comes from
 * {@code application-test.properties} and nothing reaches a developer's {@code backend/.env}.
 *
 * <p>This guards a real regression. spring-dotenv inserts {@code .env} directly after
 * {@code systemEnvironment} in the property sources, which puts it ABOVE
 * {@code application-{profile}.properties}:
 *
 * <pre>
 * system properties &gt; OS env vars &gt; .env &gt; application-test.properties &gt; application.properties
 * </pre>
 *
 * <p>So any bean reading a bare env name such as {@code ${JWT_SECRET_KEY}} cannot be overridden by
 * this profile at all. Two beans used to do exactly that, and the suite silently ran on whatever
 * the developer's {@code .env} held. Worse, {@code resend.api-key} was unset here, so
 * {@code ResendConfig}'s {@code @ConditionalOnProperty} made the mail bean's very existence depend
 * on whether a {@code .env} was present, meaning CI and local runs built different contexts.
 *
 * <p>The fix is that beans read DOTTED keys, and {@code application.properties} is the single place
 * mapping an env var onto each one. If someone reintroduces a bare uppercase name, or drops a value
 * from the test profile, the corresponding assertion below fails on a machine that has a
 * {@code .env} and passes in CI, which is precisely the divergence this exists to catch.
 */
@SpringBootTest
@ActiveProfiles("test")
class TestProfileIsolationTest extends ContainerIntegrationBase {

    @Autowired
    private Environment env;

    @Test
    @DisplayName("secrets resolve to the committed test dummies, not a developer's .env")
    void secretsComeFromTestProfile() {
        assertThat(env.getProperty("jwt.secret-key"))
                .isEqualTo("dGVzdER1bW15Snd0U2VjcmV0S2V5Rm9yVW5pdFRlc3RzMTIzNA==");
        assertThat(env.getProperty("grade.encryption.key"))
                .isEqualTo("dGVzdER1bW15R3JhZGVFbmNyeXB0aW9uS2V5MTIzNDU=");
        assertThat(env.getProperty("calendar.link-token.secret"))
                .isEqualTo("dGVzdER1bW15Q2FsZW5kYXJMaW5rVG9rZW5TZWNyZXQxMjM0");
    }

    @Test
    @DisplayName("no test can reach a real third-party service")
    void thirdPartyCredentialsAreFake() {
        assertThat(env.getProperty("resend.api-key")).isEqualTo("test-resend-key");
        assertThat(env.getProperty("gemini.api.key")).isEqualTo("test-key");
        assertThat(env.getProperty("google.oauth.client-id")).isEqualTo("test-client-id");
        assertThat(env.getProperty("google.oauth.client-secret")).isEqualTo("test-client-secret");

        // A live Resend key starts "re_" and a live Google client id ends in this suffix. Assert
        // the shapes too, so a future dummy value that happens to be real is still caught.
        assertThat(env.getProperty("resend.api-key")).doesNotStartWith("re_");
        assertThat(env.getProperty("google.oauth.client-id"))
                .doesNotEndWith(".apps.googleusercontent.com");
    }

    @Test
    @DisplayName("web config points at localhost, never a deployed host")
    void webConfigIsLocal() {
        assertThat(env.getProperty("cors.allowed-origin")).isEqualTo("http://localhost:3000");
        assertThat(env.getProperty("site.base.url.https")).isEqualTo("http://localhost:8080");
        assertThat(env.getProperty("google.oauth.redirect-uri")).startsWith("http://localhost:");
    }

    @Test
    @DisplayName("the in-process leaderboard cron cannot fire mid-run")
    void scheduledJobsAreDisabled() {
        assertThat(env.getProperty("leaderboard.recompute.cron")).isEqualTo("-");
        assertThat(env.getProperty("jobs.trigger.token")).isEqualTo("test-jobs-token");
    }

    @Test
    @DisplayName("dev profile is not active alongside test")
    void devProfileIsNotActive() {
        assertThat(env.getActiveProfiles()).containsExactly("test");
    }

    @Test
    @DisplayName("the dummy grade key is a usable AES key")
    void gradeKeyIsAValidAesLength() {
        // The converters use AES/GCM, which accepts only 128, 192 or 256 bit keys. The original
        // dummy decoded to 31 bytes, so it could never have encrypted anything. That stayed
        // invisible for as long as an exported production key kept overriding it. Asserting the
        // length here means a bad dummy fails immediately and for an obvious reason, instead of
        // surfacing as "Error attempting to apply AttributeConverter" in 33 unrelated tests.
        byte[] key = java.util.Base64.getDecoder()
                .decode(env.getProperty("grade.encryption.key", ""));
        assertThat(key.length).isIn(16, 24, 32);
    }
}
