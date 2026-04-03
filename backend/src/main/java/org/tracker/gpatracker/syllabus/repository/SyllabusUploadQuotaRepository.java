package org.tracker.gpatracker.syllabus.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.syllabus.model.SyllabusUploadQuota;

@Repository
public interface SyllabusUploadQuotaRepository extends MongoRepository<SyllabusUploadQuota, Long> {
}
