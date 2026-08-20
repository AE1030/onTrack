package org.tracker.gpatracker.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.tracker.gpatracker.tenancy.UserScopedRepositoryImpl;

/**
 * Installs {@link UserScopedRepositoryImpl} as the base class behind every JPA repository.
 *
 * <p>{@code basePackages} is spelled out rather than left to default. Declaring
 * {@code @EnableJpaRepositories} at all switches off Boot's auto-configuration of JPA repositories,
 * and the default base package is the one holding <em>this</em> class — which would find nothing and
 * silently leave the application with no repositories at all. {@code RepositoryWiringTest} counts
 * the beans so that mistake cannot be made quietly.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "org.tracker.gpatracker",
        repositoryBaseClass = UserScopedRepositoryImpl.class
)
public class JpaRepositoryConfig {
}
