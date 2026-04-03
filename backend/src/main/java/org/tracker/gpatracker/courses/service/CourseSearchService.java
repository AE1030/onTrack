package org.tracker.gpatracker.courses.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.courses.dto.CourseLookupDTO;
import org.tracker.gpatracker.courses.model.Course;
import org.tracker.gpatracker.courses.repository.CourseRepository;

@Service
public class CourseSearchService {

    private final CourseRepository courseRepository;

    public CourseSearchService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    public Page<CourseLookupDTO> searchCourses(String query, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("courseCode").ascending());
        Page<Course> courses;

        if (query == null || query.isBlank()) {
            courses = courseRepository.findAll(pageable);
        } else {
            courses = courseRepository.findByCourseCodeContainingIgnoreCase(query, pageable);
        }

        return courses.map(this::toLookupDTO);
    }

    private CourseLookupDTO toLookupDTO(Course course) {
        CourseLookupDTO dto = new CourseLookupDTO();
        dto.setId(course.getId());
        dto.setCourseCode(course.getCourseCode());
        dto.setCourseName(course.getCourseName());
        dto.setCourseCredits(course.getCourseCredits());
        return dto;
    }
}
