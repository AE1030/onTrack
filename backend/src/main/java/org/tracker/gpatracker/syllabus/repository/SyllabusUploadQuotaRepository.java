package org.tracker.gpatracker.syllabus.repository;

import org.tracker.gpatracker.syllabus.model.SyllabusUploadQuota;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

/**
 * Keyed by the owner itself, so {@code findById} is already tenant-scoped by construction and the
 * filter is belt and braces on top of it.
 */
public interface SyllabusUploadQuotaRepository
        extends UserScopedRepository<SyllabusUploadQuota, Long> {
}
