package org.tracker.gpatracker.syllabus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tracker.gpatracker.syllabus.model.CourseTermDocId;
import org.tracker.gpatracker.syllabus.model.SyllabusDocument;

import java.util.List;

/**
 * The shared catalog, so a plain {@code JpaRepository} rather than a {@code UserScopedRepository}:
 * there is no owner to scope to, and scoping it would hide the catalog from every student.
 *
 * <p>Finders reach into the composite key, hence the {@code IdCourseCode} spelling.
 */
public interface SyllabusRepository extends JpaRepository<SyllabusDocument, CourseTermDocId> {

    List<SyllabusDocument> findAllByIdCourseCodeAndIdTerm(String courseCode, String term);

    List<SyllabusDocument> findByIdTerm(String term);
}
