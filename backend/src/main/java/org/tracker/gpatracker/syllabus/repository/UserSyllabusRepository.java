package org.tracker.gpatracker.syllabus.repository;

import org.tracker.gpatracker.syllabus.model.StudentCourseTermId;
import org.tracker.gpatracker.syllabus.model.UserSyllabusDocument;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.util.Optional;

/**
 * The owner is part of the key, so a lookup by course and term is already scoped: the filter supplies
 * the {@code student_id} condition, and the remaining two columns complete the primary key.
 */
public interface UserSyllabusRepository
        extends UserScopedRepository<UserSyllabusDocument, StudentCourseTermId> {

    Optional<UserSyllabusDocument> findByIdCourseCodeAndIdTerm(String courseCode, String term);
}
