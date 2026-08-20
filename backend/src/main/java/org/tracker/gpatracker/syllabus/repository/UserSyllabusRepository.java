package org.tracker.gpatracker.syllabus.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.syllabus.model.StudentCourseTermId;
import org.tracker.gpatracker.syllabus.model.UserSyllabusDocument;

import java.util.Optional;

@Repository
public interface UserSyllabusRepository extends MongoRepository<UserSyllabusDocument, StudentCourseTermId> {
    /**
     * Scoped to the current tenant in the query itself.
     *
     * <p>The SpEL is evaluated before the query is sent, so MongoDB does the filtering and the
     * document is never loaded then discarded. This previously omitted the owner entirely and
     * relied on a comparison in the service layer after the fact.
     *
     * <p>All three criteria are components of {@code _id}, so this is served entirely by the index
     * Mongo maintains for the primary key — no collection scan and no extra index to build.
     */
    @Query(value = "{ '_id.studentId': ?#{@userContextAccessor.ownerId()}, '_id.courseCode': ?0, '_id.term': ?1 }")
    Optional<UserSyllabusDocument> findByCourseCodeAndTerm(String courseCode, String term);
}
