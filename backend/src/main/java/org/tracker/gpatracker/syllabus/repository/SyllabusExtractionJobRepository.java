package org.tracker.gpatracker.syllabus.repository;

import org.tracker.gpatracker.syllabus.model.SyllabusExtractionJob;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

public interface SyllabusExtractionJobRepository
        extends UserScopedRepository<SyllabusExtractionJob, String> {
}
