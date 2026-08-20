package org.tracker.gpatracker.tenancy;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.syllabus.model.SyllabusDocument;
import org.tracker.gpatracker.tenancy.mongo.MongoTenantListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Mongo stamp/assert contract, exercised directly against the listener.
 *
 * <p>No Spring context and no Mongo server: the listener is a pure function of the event and the
 * bound tenant, and driving it directly is what lets these cases run at all in an environment with
 * no database.
 */
class MongoTenantListenerTest {

    private static final String COLLECTION = "assessmentTable";

    private final MongoTenantListener listener = new MongoTenantListener();

    @AfterEach
    void clearTenant() {
        UserContext.clear();
    }

    private static AssessmentTableDocument owned(Long ownerId) {
        AssessmentTableDocument document = new AssessmentTableDocument();
        document.setStudentId(ownerId);
        return document;
    }

    private void beforeConvert(Object source) {
        listener.onBeforeConvert(new BeforeConvertEvent<>(source, COLLECTION));
    }

    private void afterConvert(Object source) {
        listener.onAfterConvert(new AfterConvertEvent<>(new Document(), source, COLLECTION));
    }

    @Test
    @DisplayName("an unowned document is stamped with the current tenant on write")
    void stampsOwnerOnWrite() {
        UserContext.bind(1L, 42L);
        AssessmentTableDocument document = owned(null);

        beforeConvert(document);

        assertThat(document.getStudentId()).isEqualTo(42L);
        assertThat(document.getOwnerId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("writing with no tenant bound throws instead of storing an unowned document")
    void writeWithoutTenantFailsClosed() {
        assertThatThrownBy(() -> beforeConvert(owned(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant bound");
    }

    @Test
    @DisplayName("writing a document owned by someone else throws")
    void cannotWriteAnotherTenantsDocument() {
        UserContext.bind(1L, 42L);

        assertThatThrownBy(() -> beforeConvert(owned(99L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to write");
    }

    @Test
    @DisplayName("an owner already set to the caller's own id is left alone")
    void ownWriteIsUntouched() {
        UserContext.bind(1L, 42L);
        AssessmentTableDocument document = owned(42L);

        beforeConvert(document);

        assertThat(document.getStudentId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("loading another tenant's document throws — the query was missing its scope")
    void loadingAnotherTenantsDocumentThrows() {
        UserContext.bind(1L, 42L);

        assertThatThrownBy(() -> afterConvert(owned(99L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing its owner criterion");
    }

    @Test
    @DisplayName("loading an owned document with no tenant bound fails closed")
    void loadWithoutTenantFailsClosed() {
        assertThatThrownBy(() -> afterConvert(owned(99L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant bound");
    }

    @Test
    @DisplayName("loading your own document is allowed")
    void loadingOwnDocumentIsAllowed() {
        UserContext.bind(1L, 42L);

        assertThatCode(() -> afterConvert(owned(42L))).doesNotThrowAnyException();
    }

    /**
     * The listener is registered for {@code Object}, so it sees every document in the application.
     * The shared catalog has to pass straight through it or no student can read a syllabus.
     */
    @Test
    @DisplayName("the shared syllabus catalog is untouched in both directions")
    void sharedCatalogIsIgnored() {
        UserContext.bind(1L, 42L);
        SyllabusDocument catalog = new SyllabusDocument();

        assertThatCode(() -> beforeConvert(catalog)).doesNotThrowAnyException();
        assertThatCode(() -> afterConvert(catalog)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the catalog is readable even with no tenant bound")
    void sharedCatalogNeedsNoTenant() {
        assertThatCode(() -> afterConvert(new SyllabusDocument())).doesNotThrowAnyException();
    }

    /** Migrations and scheduled recomputes legitimately cross tenants. */
    @Test
    @DisplayName("system scope may read and write across tenants")
    void systemScopeCrossesTenants() {
        UserContext.runAsSystem(() -> {
            assertThatCode(() -> afterConvert(owned(99L))).doesNotThrowAnyException();
            assertThatCode(() -> beforeConvert(owned(99L))).doesNotThrowAnyException();
        });
    }

    /**
     * System scope still cannot invent an owner out of nothing — there is no tenant to stamp with,
     * so an unowned write is a bug wherever it happens.
     */
    @Test
    @DisplayName("system scope cannot stamp an unowned document")
    void systemScopeStillNeedsAnExplicitOwner() {
        UserContext.runAsSystem(() ->
                assertThatThrownBy(() -> beforeConvert(owned(null)))
                        .isInstanceOf(IllegalStateException.class));
    }
}
