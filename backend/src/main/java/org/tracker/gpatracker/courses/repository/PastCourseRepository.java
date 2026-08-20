package org.tracker.gpatracker.courses.repository;

import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.courses.model.PastCourse;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.util.List;

@Repository
public interface PastCourseRepository extends UserScopedRepository<PastCourse, Long> {
    List<PastCourse> findByOwnerId(Long studentId);
    long countByOwnerId(Long studentId);
    void deleteByOwnerId(Long studentId);
    void deleteByOwnerIdAndNameAndUnits(Long studentId, String name, String units);
    void deleteByOwnerIdAndName(Long studentId, String name);
}
