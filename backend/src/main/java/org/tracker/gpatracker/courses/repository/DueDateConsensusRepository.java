package org.tracker.gpatracker.courses.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tracker.gpatracker.courses.model.DueDateConsensus;

import java.util.Optional;

public interface DueDateConsensusRepository extends JpaRepository<DueDateConsensus, Long> {
    Optional<DueDateConsensus> findByCourseCodeAndAssessmentName(String courseCode, String assessmentName);
}
