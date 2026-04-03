package org.tracker.gpatracker.calendar.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.calendar.model.CalendarEvents;

import java.util.Optional;

@Repository
public interface CalendarEventsRepository extends MongoRepository<CalendarEvents, String> {
    Optional<CalendarEvents> findByStudentId(Long studentId);
}
