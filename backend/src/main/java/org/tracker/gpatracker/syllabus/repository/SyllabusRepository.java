package org.tracker.gpatracker.syllabus.repository;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.syllabus.model.CourseTermId;
import org.tracker.gpatracker.syllabus.model.SyllabusDocument;
import java.util.List;
import java.util.Optional;

@Repository
public interface SyllabusRepository extends MongoRepository<SyllabusDocument, CourseTermId> {
    @Query(value = "{ '_id.course_code': ?0, '_id.term': ?1 }")
    Optional<SyllabusDocument> findByCourseCodeAndTerm(String courseCode, String term);

    @Query(value = "{ '_id.term': ?0 }")
    List<SyllabusDocument> findByTerm(String term);
}
