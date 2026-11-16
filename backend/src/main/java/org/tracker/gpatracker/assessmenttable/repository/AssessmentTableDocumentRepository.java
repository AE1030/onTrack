package org.tracker.gpatracker.assessmenttable.repository;

import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.util.List;
import java.util.Optional;

/**
 * Finders name the owner property {@code ownerId}, which is mapped to the {@code student_id} column.
 * The owner argument is still passed explicitly even though the Hibernate filter adds the same
 * condition: the filter is a backstop, and a query that says what it scopes to is easier to read
 * than one that relies on ambient state.
 */
public interface AssessmentTableDocumentRepository
        extends UserScopedRepository<AssessmentTableDocument, String> {

    Optional<AssessmentTableDocument> findByOwnerIdAndCourseCodeAndTerm(Long ownerId, String courseCode, String term);

    List<AssessmentTableDocument> findByOwnerId(Long ownerId);

    /**
     * Transactional here rather than at the call site: a derived delete loads the rows and calls
     * {@code EntityManager.remove()} on each, which throws without an active transaction, and
     * declared query methods do not inherit the {@code @Transactional} that
     * {@code SimpleJpaRepository} puts on its built-in methods. Putting it on the service instead
     * would hold a transaction open across the calendar listeners' HTTP calls to Google.
     */
    @Transactional
    void deleteByOwnerIdAndCourseCodeAndTerm(Long ownerId, String courseCode, String term);
}
