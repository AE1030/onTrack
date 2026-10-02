package org.tracker.gpatracker.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts one PostgreSQL container for the whole test run.
 *
 * <p>The engine matches production — Postgres 16, not H2 — so schema generation, type mapping,
 * jsonb behaviour and SQL dialect behave in tests exactly as they do in the running application.
 * That matters more since the Mongo collections were folded in: the assessment schemes, calendar
 * projections and extracted grading schemes are all jsonb now, and H2's JSON support would not
 * exercise the same code paths.
 *
 * <p>The container is started once in a static initializer and deliberately never stopped, rather
 * than being managed by {@code @Testcontainers}/{@code @Container}. Those annotations bind the
 * container lifecycle to a single test class: JUnit stops them in that class's {@code afterAll},
 * while Spring keeps the application context — and its Hikari pool — cached across every class
 * sharing the same configuration. The second test class would then start a fresh container on a new
 * random port while the cached pool still dialled the dead one, failing with "connection refused".
 * Starting once here keeps the port stable for as long as the cached context lives. Testcontainers'
 * Ryuk sidecar removes the container when the JVM exits.
 *
 * <p>{@code @ServiceConnection} wires the container's host/port into Spring Boot auto-configuration,
 * so no JDBC URL needs to be hardcoded in test properties.
 *
 * <p>The {@code @TestPropertySource} is what makes the test profile hermetic, and it is not
 * redundant with {@code @ActiveProfiles("test")}. Loading the same file as a profile is not enough,
 * because Spring's {@code SystemEnvironmentPropertySource} performs relaxed binding: a request for
 * {@code jwt.secret-key} also matches an exported {@code JWT_SECRET_KEY}, and it matches it at
 * {@code systemEnvironment} precedence, which outranks every
 * {@code application-{profile}.properties}. A developer with onTrack variables exported therefore
 * ran the whole suite on their own production secrets, against a production CORS origin, with a
 * live Resend client. {@code TestPropertySourceUtils} installs these properties with
 * {@code addFirst}, above {@code systemEnvironment}, so the committed dummies win no matter what
 * the surrounding shell holds. {@code TestProfileIsolationTest} asserts exactly that.
 */
@TestPropertySource(locations = "classpath:application-test.properties")
public abstract class ContainerIntegrationBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }
}
