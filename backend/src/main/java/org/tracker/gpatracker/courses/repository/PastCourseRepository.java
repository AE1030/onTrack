package org.tracker.gpatracker.courses.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.courses.model.PastCourse;

import java.util.List;

@Repository
public interface PastCourseRepository extends JpaRepository<PastCourse, Long> {
    List<PastCourse> findByStudentId(Long studentId);
    long countByStudentId(Long studentId);
    void deleteByStudentId(Long studentId);
    void deleteByStudentIdAndNameAndUnits(Long studentId, String name, String units);
    void deleteByStudentIdAndName(Long studentId, String name);
}
