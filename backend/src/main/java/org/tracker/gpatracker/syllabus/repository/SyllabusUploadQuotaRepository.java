package org.tracker.gpatracker.syllabus.repository;

import org.tracker.gpatracker.syllabus.model.SyllabusUploadQuota;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.util.Optional;

public interface SyllabusUploadQuotaRepository
        extends UserScopedRepository<SyllabusUploadQuota, Long> {

    /** At most one row per student; the unique constraint on student_id is what guarantees it. */
    Optional<SyllabusUploadQuota> findByOwnerId(Long ownerId);
}
