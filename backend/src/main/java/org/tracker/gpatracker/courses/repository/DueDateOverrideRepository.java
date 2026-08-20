package org.tracker.gpatracker.courses.repository;

import org.tracker.gpatracker.courses.model.DueDateOverride;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.util.List;

public interface DueDateOverrideRepository extends UserScopedRepository<DueDateOverride, Long> {
    boolean  existsByOwnerIdAndAssessmentNameAndCourseCode(Long studentId, String assessmentName, String courseCode);

    void deleteByOwnerIdAndAssessmentNameAndCourseCode(Long studentId, String assessmentName, String courseCode);
    List<DueDateOverride> findByOwnerId(Long studentId);
}
