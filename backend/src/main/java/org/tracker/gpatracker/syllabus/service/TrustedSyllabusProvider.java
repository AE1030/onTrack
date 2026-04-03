package org.tracker.gpatracker.syllabus.service;

import org.springframework.stereotype.Component;
import org.tracker.gpatracker.syllabus.model.AbstractSyllabusDocument;
import org.tracker.gpatracker.syllabus.repository.SyllabusRepository;

import java.util.Optional;

@Component
public class TrustedSyllabusProvider implements SyllabusDocumentProvider {

    private final SyllabusRepository syllabusRepository;

    public TrustedSyllabusProvider(SyllabusRepository syllabusRepository) {
        this.syllabusRepository = syllabusRepository;
    }

    @Override
    public Optional<AbstractSyllabusDocument> findByCourseCodeAndTerm(String courseCode, String term) {
        return syllabusRepository.findByCourseCodeAndTerm(courseCode, term)
                .map(doc -> (AbstractSyllabusDocument) doc);
    }

    @Override
    public int priority() {
        return 1;
    }
}
