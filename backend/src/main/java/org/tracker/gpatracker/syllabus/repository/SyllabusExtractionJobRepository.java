package org.tracker.gpatracker.syllabus.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.syllabus.model.SyllabusExtractionJob;

@Repository
public interface SyllabusExtractionJobRepository extends MongoRepository<SyllabusExtractionJob, String> {
}
