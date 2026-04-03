package org.tracker.gpatracker.syllabus.service;

import org.tracker.gpatracker.syllabus.model.AbstractSyllabusDocument;

import java.util.Optional;

public interface SyllabusDocumentProvider {
    Optional<AbstractSyllabusDocument> findByCourseCodeAndTerm(String courseCode, String term);
    int priority();
}
