package org.tracker.gpatracker.tenancy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.test.context.ActiveProfiles;

import org.tracker.gpatracker.support.ContainerIntegrationBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hazard 7. Declaring {@code @EnableJpaRepositories} to install a custom repository base class
 * switches off Boot's auto-configuration of JPA repositories. Get the {@code basePackages} wrong and
 * repositories silently stop being created — which surfaces as a confusing missing-bean failure far
 * from the cause, or not at all if the affected repository is only used on one code path.
 *
 * <p>These counts are the ones observed before the base class was introduced. If a repository is
 * legitimately added or removed later, update the constant deliberately rather than loosening the
 * assertion.
 */
@SpringBootTest
@ActiveProfiles("test")
class RepositoryWiringTest extends ContainerIntegrationBase {

    private static final int EXPECTED_JPA_REPOSITORIES = 10;
    private static final int EXPECTED_MONGO_REPOSITORIES = 7;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("every JPA repository is still wired")
    void jpaRepositoriesAreAllPresent() {
        assertThat(context.getBeanNamesForType(JpaRepository.class))
                .as("a drop here means @EnableJpaRepositories is not covering the whole application")
                .hasSize(EXPECTED_JPA_REPOSITORIES);
    }

    /**
     * Mongo repositories stay on Boot's auto-configuration. They are counted because the failure
     * mode of an over-broad JPA scan is for Spring Data to claim them for the wrong store.
     */
    @Test
    @DisplayName("every Mongo repository is still wired, and still Mongo")
    void mongoRepositoriesAreAllPresent() {
        assertThat(context.getBeanNamesForType(MongoRepository.class))
                .hasSize(EXPECTED_MONGO_REPOSITORIES);
    }
}
