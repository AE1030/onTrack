package org.tracker.gpatracker.calendar.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.calendar.model.GoogleCalendarExport;

import java.util.Optional;

@Repository
public interface GoogleCalendarExportRepository extends MongoRepository<GoogleCalendarExport, String> {
    Optional<GoogleCalendarExport> findTopByStudentIdOrderByExportedAtDesc(Long studentId);
}
