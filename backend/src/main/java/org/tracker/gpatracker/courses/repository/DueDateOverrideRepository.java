package org.tracker.gpatracker.courses.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tracker.gpatracker.courses.model.DueDateOverride;

import java.util.List;

public interface DueDateOverrideRepository extends JpaRepository<DueDateOverride, Long> {
    boolean  existsByStudentIdAndAssessmentNameAndCourseCode(Long studentId, String assessmentName, String courseCode);

    void deleteByStudentIdAndAssessmentNameAndCourseCode(Long studentId, String assessmentName, String courseCode);
    List<DueDateOverride> findByStudentId(Long studentId);
}
