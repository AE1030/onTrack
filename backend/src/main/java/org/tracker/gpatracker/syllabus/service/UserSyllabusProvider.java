package org.tracker.gpatracker.syllabus.service;

import org.springframework.stereotype.Component;
import org.tracker.gpatracker.syllabus.model.AbstractSyllabusDocument;
import org.tracker.gpatracker.syllabus.repository.UserSyllabusRepository;

import java.util.Optional;

@Component
public class UserSyllabusProvider implements SyllabusDocumentProvider {

    private final UserSyllabusRepository userSyllabusRepository;

    public UserSyllabusProvider(UserSyllabusRepository userSyllabusRepository) {
        this.userSyllabusRepository = userSyllabusRepository;
    }

    @Override
    public Optional<AbstractSyllabusDocument> findByCourseCodeAndTerm(String courseCode, String term) {
        return userSyllabusRepository.findByIdCourseCodeAndIdTerm(courseCode, term)
                .map(doc -> (AbstractSyllabusDocument) doc);
    }

    @Override
    public int priority() {
        return 0;
    }
}
