package org.tracker.gpatracker.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts one PostgreSQL and one MongoDB container for the whole test run.
 *
 * <p>The engines match production — Postgres 16, not H2 — so schema generation, type mapping,
 * and SQL dialect behave in tests exactly as they do in the running application.
 *
 * <p>Containers are started once in a static initializer and deliberately never stopped, rather
 * than being managed by {@code @Testcontainers}/{@code @Container}. Those annotations bind the
 * container lifecycle to a single test class: JUnit stops them in that class's {@code afterAll},
 * while Spring keeps the application context — and its Hikari pool — cached across every class
 * sharing the same configuration. The second test class would then start fresh containers on new
 * random ports while the cached pool still dialled the dead ones, failing with
 * "connection refused". Starting once here keeps the ports stable for as long as the cached
 * context lives. Testcontainers' Ryuk sidecar removes the containers when the JVM exits.
 *
 * <p>{@code @ServiceConnection} wires each container's host/port into Spring Boot
 * auto-configuration, so no JDBC URL or Mongo URI needs to be hardcoded in test properties.
 */
public abstract class ContainerIntegrationBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    static {
        POSTGRES.start();
        MONGO.start();
    }
}
