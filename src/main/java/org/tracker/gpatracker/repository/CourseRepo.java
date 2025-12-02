package org.tracker.gpatracker.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.model.Course;

import java.util.Optional;


@Repository
public interface CourseRepo extends JpaRepository<Course, Integer>{
    Course findById(Long course_id);
    Optional<Course> findBycourseCode(String courseCode);

}

