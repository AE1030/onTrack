package org.tracker.gpatracker.syllabus.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.tracker.gpatracker.syllabus.dto.AssessmentTableDTO;
import org.tracker.gpatracker.syllabus.model.SyllabusDocument;
import org.tracker.gpatracker.syllabus.repository.SyllabusRepository;

import org.junit.jupiter.api.Disabled;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.tracker.gpatracker.support.ContainerIntegrationBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that runs getNormalizedAssessmentItems against every
 * SyllabusDocument in the MongoDB "syllabuses" collection.
 *
 * <p>Runs on the "test" profile against the Testcontainers Mongo from
 * {@link ContainerIntegrationBase}. That container starts empty, so this stays disabled: it
 * asserts over real syllabus data and has nothing to read until the collection is seeded.
 */
@SpringBootTest
@ActiveProfiles("test")
@Disabled("Asserts over real syllabus data; seed the Mongo container before running manually")
class SyllabusAssessmentServiceIntegrationTest extends ContainerIntegrationBase {

    @Autowired
    private SyllabusRepository syllabusRepository;

    @Autowired
    private SyllabusAssessmentService syllabusAssessmentService;

    @Test
    void allSyllabusDocuments_shouldProduceValidAssessmentTables() {
        List<SyllabusDocument> allDocs = syllabusRepository.findByTerm("Winter 2026");
        assertThat(allDocs)
                .as("No syllabuses found for Winter 2026")
                .isNotEmpty();

        for (SyllabusDocument doc : allDocs) {
            String label = doc.getCourseCode() + " " + doc.getTerm();

            Map<String, List<AssessmentTableDTO>> result =
                    syllabusAssessmentService.getNormalizedAssessmentItems(doc);

            assertThat(result)
                    .as("Result should not be null for %s", label)
                    .isNotNull();

            // A syllabus may have schemes defined but with empty assessment lists
            // (e.g. extraction couldn't find grading info). That's valid -- result will be empty.

            result.forEach((schemeName, assessments) -> {
                assertThat(assessments)
                        .as("Scheme '%s' in %s should not be empty", schemeName, label)
                        .isNotEmpty();

                for (AssessmentTableDTO dto : assessments) {
                    assertThat(dto.getAssessmentName())
                            .as("Assessment name missing in scheme '%s' of %s", schemeName, label)
                            .isNotBlank();

                    assertThat(dto.getWeights())
                            .as("Weights missing for '%s' in scheme '%s' of %s",
                                    dto.getAssessmentName(), schemeName, label)
                            .isNotNull()
                            .isNotEmpty();
                }
            });
        }
    }

    @Test
    void allSyllabusDocuments_weightsShouldNotExceed100PerScheme() {
        List<SyllabusDocument> allDocs = syllabusRepository.findAll();

        for (SyllabusDocument doc : allDocs) {
            String label = doc.getCourseCode() + " " + doc.getTerm();

            Map<String, List<AssessmentTableDTO>> result =
                    syllabusAssessmentService.getNormalizedAssessmentItems(doc);

            result.forEach((schemeName, assessments) -> {
                BigDecimal totalWeight = BigDecimal.ZERO;
                for (AssessmentTableDTO dto : assessments) {
                    if (dto.getWeights() != null && dto.getWeights().length > 0
                            && dto.getWeights()[0] != null) {
                        totalWeight = totalWeight.add(dto.getWeights()[0]);
                    }
                }
                // Weights should not exceed 100 (allow small tolerance for rounding)
                assertThat(totalWeight.doubleValue())
                        .as("Total weight for scheme '%s' in %s is %s which exceeds 100",
                                schemeName, label, totalWeight)
                        .isLessThanOrEqualTo(100.01);
            });
        }
    }
}
