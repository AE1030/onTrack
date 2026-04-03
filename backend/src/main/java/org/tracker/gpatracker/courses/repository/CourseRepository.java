package org.tracker.gpatracker.courses.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.courses.model.Course;

import java.util.Optional;


@Repository
public interface CourseRepository extends JpaRepository<Course, Long>{
    Optional<Course> findBycourseCode(String courseCode);

    Page<Course> findByCourseCodeContainingIgnoreCase(String courseCode, Pageable pageable);
}
