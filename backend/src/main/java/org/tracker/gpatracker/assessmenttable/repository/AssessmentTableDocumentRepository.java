package org.tracker.gpatracker.assessmenttable.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssessmentTableDocumentRepository extends MongoRepository<AssessmentTableDocument, String> {
    Optional<AssessmentTableDocument> findByStudentIdAndCourseCodeAndTerm(Long studentId, String courseCode, String term);
    List<AssessmentTableDocument> findByStudentId(Long studentId);
    void deleteByStudentIdAndCourseCodeAndTerm(Long studentId, String courseCode, String term);
}
