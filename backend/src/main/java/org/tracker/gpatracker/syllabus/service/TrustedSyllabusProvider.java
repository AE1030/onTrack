package org.tracker.gpatracker.syllabus.service;

import org.springframework.stereotype.Component;
import org.tracker.gpatracker.syllabus.model.AbstractSyllabusDocument;
import org.tracker.gpatracker.syllabus.model.AssessmentItem;
import org.tracker.gpatracker.syllabus.model.Assessments;
import org.tracker.gpatracker.syllabus.model.GradingScheme;
import org.tracker.gpatracker.syllabus.model.SchemeDefinition;
import org.tracker.gpatracker.syllabus.model.SyllabusDocument;
import org.tracker.gpatracker.syllabus.repository.SyllabusRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class TrustedSyllabusProvider implements SyllabusDocumentProvider {

    /**
     * Which section to serve when a course publishes several.
     *
     * <p>The catalog now keeps every section rather than letting them overwrite each other, so
     * something has to choose. Ordering, best first:
     *
     * <ol>
     *   <li>Sections whose extraction actually produced a grading scheme. A section with no usable
     *       scheme is useless to the calculator, and its only effect would be to hide a sibling
     *       that has one.
     *   <li>Then the richest extraction, by assessment count. Where two sections of a course share
     *       a syllabus, the fuller extraction is the one that read more of it.
     *   <li>Then {@code doc_code} ascending, purely so the choice is stable. Without this the
     *       result would drift between calls on the database's return order, and the same student would
     *       see a different grading scheme on different days.
     * </ol>
     *
     * <p>This is a heuristic, not a claim that sections are interchangeable. Where they genuinely
     * differ, serving one section's scheme to another section's student is wrong, and the real fix
     * is to key a student's course enrolment to a section. That is a larger change; this keeps the
     * current single-scheme contract while the data underneath stops losing sections.
     */
    private static final Comparator<SyllabusDocument> SECTION_PREFERENCE =
            Comparator.comparing(TrustedSyllabusProvider::hasUsableScheme).reversed()
                    .thenComparing(Comparator.comparingInt(TrustedSyllabusProvider::assessmentCount).reversed())
                    .thenComparing(TrustedSyllabusProvider::docCode);

    private final SyllabusRepository syllabusRepository;

    public TrustedSyllabusProvider(SyllabusRepository syllabusRepository) {
        this.syllabusRepository = syllabusRepository;
    }

    @Override
    public Optional<AbstractSyllabusDocument> findByCourseCodeAndTerm(String courseCode, String term) {
        return syllabusRepository.findAllByIdCourseCodeAndIdTerm(courseCode, term).stream()
                .min(SECTION_PREFERENCE)
                .map(AbstractSyllabusDocument.class::cast);
    }

    @Override
    public int priority() {
        return 1;
    }

    private static boolean hasUsableScheme(SyllabusDocument doc) {
        return assessmentCount(doc) > 0;
    }

    private static int assessmentCount(SyllabusDocument doc) {
        Map<String, SchemeDefinition> schemes = schemesOf(doc);
        if (schemes == null) {
            return 0;
        }
        int count = 0;
        for (SchemeDefinition scheme : schemes.values()) {
            List<AssessmentItem> items = scheme == null ? null : scheme.getAssessmentItemList();
            if (items != null) {
                count += items.size();
            }
        }
        return count;
    }

    private static Map<String, SchemeDefinition> schemesOf(SyllabusDocument doc) {
        Assessments assessments = doc.getAssessments();
        GradingScheme gradingScheme = assessments == null ? null : assessments.getGradingScheme();
        return gradingScheme == null ? null : gradingScheme.getSchemes();
    }

    /** Never null in practice, but the comparator must not throw on a document written by hand. */
    private static String docCode(SyllabusDocument doc) {
        if (doc.getId() != null && doc.getId().getDocCode() != null) {
            return doc.getId().getDocCode();
        }
        return doc.getDocCode() == null ? "" : doc.getDocCode();
    }
}
