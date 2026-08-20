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
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.repository.support.Repositories;
import org.springframework.test.context.ActiveProfiles;
import org.tracker.gpatracker.syllabus.model.SyllabusDocument;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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
    private MongoMappingContext mongoMappingContext;

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

    @Test
    @DisplayName("every entity with a student_id column is UserOwned")
    void everyEntityWithAnOwnerColumnIsUserOwned() {
        List<String> unmarked = new ArrayList<>();

        for (EntityType<?> type : em.getMetamodel().getEntities()) {
            Class<?> javaType = type.getJavaType();
            if (UserOwned.class.isAssignableFrom(javaType)) {
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
    @DisplayName("every Mongo document carrying an owner participates in the tenant contract")
    void everyOwnedMongoDocumentIsUserOwned() {
        List<String> unmarked = new ArrayList<>();

        for (MongoPersistentEntity<?> entity : collectionRoots()) {
            Class<?> javaType = entity.getType();
            if (UserOwned.class.isAssignableFrom(javaType)) {
                continue;
            }
            if (entity.getPersistentProperty("studentId") != null) {
                unmarked.add(javaType.getSimpleName());
            }
        }

        assertThat(unmarked)
                .as("these store an owner but are neither stamped on write nor asserted on read")
                .isEmpty();
    }

    /**
     * The owner field must be stored as {@code studentId}, never {@code student_id}.
     *
     * <p>Four of the five owned collections already hold documents keyed on the camelCase name.
     * Introducing a {@code @Field("student_id")} anywhere in the hierarchy would make every one of
     * those existing documents deserialize with a null owner and then trip the load-time assert —
     * a silent four-collection outage dressed up as a naming tidy-up.
     */
    @Test
    @DisplayName("the Mongo owner field is stored as studentId, not student_id")
    void ownerFieldNameIsStable() {
        List<String> wrong = new ArrayList<>();

        for (MongoPersistentEntity<?> entity : collectionRoots()) {
            if (!UserOwned.class.isAssignableFrom(entity.getType())) {
                continue;
            }
            var owner = entity.getPersistentProperty("studentId");
            if (owner == null) {
                wrong.add(entity.getType().getSimpleName() + " (no owner property at all)");
                continue;
            }
            // SyllabusUploadQuota carries its owner *as* the @Id, so it is stored under _id by
            // design — self-scoping rather than misnamed.
            if (owner.isIdProperty()) {
                continue;
            }
            if (!"studentId".equals(owner.getFieldName())) {
                wrong.add(entity.getType().getSimpleName() + " stores it as " + owner.getFieldName());
            }
        }

        assertThat(wrong).isEmpty();
    }

    /**
     * {@code getOwnerId}/{@code setOwnerId} are accessors over the existing {@code studentId}, not a
     * second stored field. Spring Data will happily persist a getter/setter pair that has no backing
     * field, so if the {@code @Transient} is ever dropped, every owned document silently grows a
     * duplicate {@code ownerId} key.
     */
    @Test
    @DisplayName("ownerId is not persisted as a second Mongo field")
    void ownerIdIsNotPersisted() {
        List<String> duplicated = new ArrayList<>();

        for (MongoPersistentEntity<?> entity : collectionRoots()) {
            if (entity.getPersistentProperty("ownerId") != null) {
                duplicated.add(entity.getType().getSimpleName());
            }
        }

        assertThat(duplicated)
                .as("ownerId must stay @Transient — it is a view of studentId, not storage")
                .isEmpty();
    }

    @Test
    @DisplayName("the shared syllabus catalog is deliberately not tenant-scoped")
    void sharedCatalogIsNotOwned() {
        assertThat(UserOwned.class.isAssignableFrom(SyllabusDocument.class))
                .as("SyllabusDocument is a global catalog; scoping it would hide it from every student")
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
            // JPA only. Owned Mongo documents are covered by the listener instead — there is no
            // Hibernate filter for them to participate in.
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

    /**
     * Top-level {@code @Document} types only.
     *
     * <p>The mapping context also registers nested value types — composite id classes such as
     * {@code StudentCourseTermId} — and those are not collections in their own right, so the
     * ownership rules do not apply to them.
     */
    private List<MongoPersistentEntity<?>> collectionRoots() {
        List<MongoPersistentEntity<?>> roots = new ArrayList<>();
        for (MongoPersistentEntity<?> entity : mongoMappingContext.getPersistentEntities()) {
            if (entity.getType().isAnnotationPresent(
                    org.springframework.data.mongodb.core.mapping.Document.class)) {
                roots.add(entity);
            }
        }
        assertThat(roots).as("mapping context found no @Document types at all").isNotEmpty();
        return roots;
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
