package org.tracker.gpatracker.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import org.hibernate.engine.spi.LoadQueryInfluencers;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.support.Repositories;
import org.springframework.test.context.ActiveProfiles;
import org.tracker.gpatracker.leaderboard.model.LeaderboardEntry;
import org.tracker.gpatracker.leaderboard.model.LeaderboardRankHistory;
import org.tracker.gpatracker.leaderboard.model.LeaderboardProfile;
import org.tracker.gpatracker.leaderboard.model.SeasonBaseline;
import org.tracker.gpatracker.leaderboard.model.TranscriptUpload;
import org.tracker.gpatracker.syllabus.model.SyllabusDocument;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.tracker.gpatracker.support.ContainerIntegrationBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards, so a future entity that forgets an annotation fails in CI rather than
 * shipping as a silently unfiltered table.
 */
@SpringBootTest
@ActiveProfiles("test")
class TenantMappingTest extends ContainerIntegrationBase {

    @Autowired
    private EntityManager em;

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * The highest-value assertion here. {@code @Filter} is bound from the concrete {@code @Entity}
     * class and is <b>not</b> inherited from a {@code @MappedSuperclass} — omitting it produces no
     * error and no warning, just an unfiltered table. Asking Hibernate directly whether each entity
     * is affected by the filter is the only way to catch that.
     */
    @Test
    @DisplayName("every UserOwned entity is actually affected by the owner filter")
    void everyUserOwnedEntityIsFiltered() {
        SessionFactoryImplementor sessionFactory =
                em.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class);

        LoadQueryInfluencers influencers = new LoadQueryInfluencers(sessionFactory);
        influencers.enableFilter(OwnerFilter.NAME);

        List<String> unfiltered = new ArrayList<>();
        for (EntityType<?> type : em.getMetamodel().getEntities()) {
            Class<?> javaType = type.getJavaType();
            if (!UserOwned.class.isAssignableFrom(javaType)) {
                continue;
            }
            boolean affected = sessionFactory.getMappingMetamodel()
                    .getEntityDescriptor(javaType)
                    .isAffectedByEnabledFilters(influencers, false);
            if (!affected) {
                unfiltered.add(javaType.getSimpleName());
            }
        }

        assertThat(unfiltered)
                .as("these implement UserOwned but carry no @Filter, so their tables are wide open")
                .isEmpty();
    }

    /**
     * The published board and its history: the only two tables carrying an owner column that sit
     * outside the filter contract.
     *
     * <p>Listed by name rather than detected by a rule, so a third unfiltered table cannot appear
     * by accident. Both are safe for the same reason: a board that only shows you your own row is
     * not a board, and neither table holds anything private. The baseline and target that genuinely
     * are grades live on {@code SeasonBaseline}, which is filtered. {@code LeaderboardRankHistory}
     * holds a rank and a score, both of which were on the board already.
     */
    private static final Set<Class<?>> PUBLISHED_ON_PURPOSE =
            Set.of(LeaderboardEntry.class, LeaderboardRankHistory.class);

    @Test
    @DisplayName("every entity with a student_id column is UserOwned, bar the published board")
    void everyEntityWithAnOwnerColumnIsUserOwned() {
        List<String> unmarked = new ArrayList<>();

        for (EntityType<?> type : em.getMetamodel().getEntities()) {
            Class<?> javaType = type.getJavaType();
            if (UserOwned.class.isAssignableFrom(javaType) || PUBLISHED_ON_PURPOSE.contains(javaType)) {
                continue;
            }
            if (declaresOwnerColumn(javaType)) {
                unmarked.add(javaType.getSimpleName());
            }
        }

        assertThat(unmarked)
                .as("these have a student_id column but do not participate in the filter contract")
                .isEmpty();
    }

    /**
     * The other half of that exemption: the three tables backing the board that are <em>not</em>
     * public must be owned. Without this, "leaderboard entities are exempt" could quietly widen
     * from one class to the whole package.
     */
    @Test
    @DisplayName("the leaderboard's private tables are tenant-scoped")
    void leaderboardSupportingTablesAreOwned() {
        assertThat(UserOwned.class.isAssignableFrom(LeaderboardProfile.class)).isTrue();
        assertThat(UserOwned.class.isAssignableFrom(SeasonBaseline.class)).isTrue();
        assertThat(UserOwned.class.isAssignableFrom(TranscriptUpload.class)).isTrue();

        assertThat(UserOwned.class.isAssignableFrom(LeaderboardEntry.class))
                .as("the board is the deliberate exception; scoping it would hide every other row")
                .isFalse();

        assertThat(UserOwned.class.isAssignableFrom(LeaderboardRankHistory.class))
                .as("rank history publishes the same figures the board already does")
                .isFalse();
    }

    @Test
    @DisplayName("the owner filter is auto-enabled and applies to load-by-key")
    void filterDefinitionHasTheAttributesTheDesignRelieson() {
        SessionFactoryImplementor sessionFactory =
                em.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class);

        var definition = sessionFactory.getFilterDefinition(OwnerFilter.NAME);

        assertThat(definition).as("owner filter must be registered").isNotNull();
        assertThat(definition.isAutoEnabled())
                .as("without autoEnabled nothing turns the filter on per session")
                .isTrue();
        assertThat(definition.isAppliedToLoadByKey())
                .as("without applyToLoadByKey, findById bypasses the filter entirely")
                .isTrue();
    }

    @Test
    @DisplayName("the shared syllabus catalog is deliberately not tenant-scoped")
    void sharedCatalogIsNotOwned() {
        assertThat(UserOwned.class.isAssignableFrom(SyllabusDocument.class))
                .as("SyllabusDocument is a global catalog; scoping it would hide it from every student")
                .isFalse();
        assertThat(SyllabusDocument.class.isAnnotationPresent(org.hibernate.annotations.Filter.class))
                .as("nor may it carry the owner filter, which would scope it just as effectively")
                .isFalse();
    }

    @Test
    @DisplayName("every repository over an owned entity extends UserScopedRepository")
    void everyOwnedEntityRepositoryIsUserScoped() {
        Repositories repositories = new Repositories(applicationContext);
        List<String> unmarked = new ArrayList<>();

        for (Class<?> domainType : repositories) {
            if (!UserOwned.class.isAssignableFrom(domainType)) {
                continue;
            }
            Class<?> repositoryInterface = repositories.getRequiredRepositoryInformation(domainType)
                    .getRepositoryInterface();
            if (!JpaRepository.class.isAssignableFrom(repositoryInterface)) {
                continue;
            }
            if (!UserScopedRepository.class.isAssignableFrom(repositoryInterface)) {
                unmarked.add(repositoryInterface.getSimpleName());
            }
        }

        assertThat(unmarked)
                .as("these expose an owned entity but opt out of the filter contract")
                .isEmpty();
    }

    /**
     * Hibernate filters do not apply to native queries. One native query exists today
     * ({@code RolesRepo}), on the global {@code role} table with an explicit user id, which is fine
     * — but a native query on an owned table would read straight past the filter.
     */
    @Test
    @DisplayName("no user-scoped repository declares a native query")
    void userScopedRepositoriesDeclareNoNativeQueries() {
        Repositories repositories = new Repositories(applicationContext);
        List<String> offenders = new ArrayList<>();

        for (Class<?> domainType : repositories) {
            Class<?> repositoryInterface = repositories.getRequiredRepositoryInformation(domainType)
                    .getRepositoryInterface();
            if (!UserScopedRepository.class.isAssignableFrom(repositoryInterface)) {
                continue;
            }
            for (Method method : repositoryInterface.getMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query != null && query.nativeQuery()) {
                    offenders.add(repositoryInterface.getSimpleName() + "." + method.getName());
                }
            }
        }

        assertThat(offenders)
                .as("a native query on an owned table bypasses the filter entirely")
                .isEmpty();
    }

    /** True if the class, or any mapped superclass, maps a column named student_id. */
    private boolean declaresOwnerColumn(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                jakarta.persistence.Column column = field.getAnnotation(jakarta.persistence.Column.class);
                if (column != null && "student_id".equals(column.name())) {
                    return true;
                }
                jakarta.persistence.JoinColumn join = field.getAnnotation(jakarta.persistence.JoinColumn.class);
                if (join != null && "student_id".equals(join.name())) {
                    return true;
                }
            }
        }
        return false;
    }
}
