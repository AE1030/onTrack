package org.tracker.gpatracker.tenancy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.test.context.ActiveProfiles;

import org.tracker.gpatracker.support.ContainerIntegrationBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hazard 7. Declaring {@code @EnableJpaRepositories} to install a custom repository base class
 * switches off Boot's auto-configuration of JPA repositories. Get the {@code basePackages} wrong and
 * repositories silently stop being created — which surfaces as a confusing missing-bean failure far
 * from the cause, or not at all if the affected repository is only used on one code path.
 *
 * <p>If a repository is legitimately added or removed later, update the constant deliberately rather
 * than loosening the assertion.
 */
@SpringBootTest
@ActiveProfiles("test")
class RepositoryWiringTest extends ContainerIntegrationBase {

    // 10 when this was written, then 14 with the leaderboard's four: entry, profile, season
    // baseline, transcript upload. Avenue's d2l_token and d2l_api_audit briefly took this to 16
    // without the constant ever being updated; both were removed with the integration. 15 with
    // refresh_token, then 13 once due-date consensus and its overrides were deleted, then 14 when
    // refresh_token's repository landed without the count being updated with it.
    //
    // 21 since the Mongo collections were folded into Postgres, which moved seven repositories
    // across in one go: assessment table, calendar events, Google calendar export, syllabus catalog,
    // user syllabus extraction, extraction job, upload quota.
    private static final int EXPECTED_JPA_REPOSITORIES = 21;

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
     * The counterpart to the count above, and the reason the Mongo half of this test could simply be
     * deleted rather than inverted: with the driver off the classpath there is no second store for
     * Spring Data to mistakenly claim a repository for. If MongoDB ever returns, the over-broad-scan
     * hazard returns with it and this assertion should come back.
     */
    @Test
    @DisplayName("no Spring Data MongoDB support remains on the classpath")
    void mongoSupportIsGone() {
        assertThat(classPresent("org.springframework.data.mongodb.repository.MongoRepository"))
                .as("spring-boot-starter-data-mongodb is back; V16 folded these collections into Postgres")
                .isFalse();
        assertThat(classPresent("com.mongodb.client.MongoClient"))
                .as("the Mongo driver is back on the classpath")
                .isFalse();
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, RepositoryWiringTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
