package org.tracker.gpatracker.syllabus.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.syllabus.model.CourseTermId;
import org.tracker.gpatracker.syllabus.model.UserSyllabusDocument;

import java.util.Optional;

@Repository
public interface UserSyllabusRepository extends MongoRepository<UserSyllabusDocument, CourseTermId> {
    @Query(value = "{ '_id.course_code': ?0, '_id.term': ?1 }")
    Optional<UserSyllabusDocument> findByCourseCodeAndTerm(String courseCode, String term);
}
