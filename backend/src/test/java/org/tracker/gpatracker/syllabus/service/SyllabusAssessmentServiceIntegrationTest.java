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
 * Integration test that runs getNormalizedAssessmentItems against every row in the shared
 * {@code syllabus} catalog.
 *
 * <p>Runs on the "test" profile against the Testcontainers Postgres from
 * {@link ContainerIntegrationBase}. That database starts empty, so this stays disabled: it
 * asserts over real syllabus data and has nothing to read until the table is seeded.
 *
 * <p><strong>These assertions now also run for real, elsewhere.</strong> Both of them were ported
 * into the verify stage of {@code tools/syllabus-pipeline}, which checks every catalog document on
 * every scheduled run and writes what it finds to {@code syllabus_pipeline_run}. That is where a bad
 * extraction actually gets caught, because it runs against the real catalog rather than an empty
 * container. The pipeline's copy also enforces the parts of the extraction contract this class
 * never checked: the bonus rules, replacement-rule completeness, and due-date counts matching
 * {@code occurrence.total}.
 *
 * <p>What this class is still the right home for is the normalizer itself. It exercises
 * {@code getNormalizedAssessmentItems}, which the Python checks cannot see at all. Give it fixtures
 * rather than a live catalog and it can be enabled; until then {@link SyllabusAssessmentServiceTest}
 * covers that logic against hand-built documents.
 */
@SpringBootTest
@ActiveProfiles("test")
@Disabled("Asserts over real syllabus data; seed the syllabus table before running manually")
class SyllabusAssessmentServiceIntegrationTest extends ContainerIntegrationBase {

    @Autowired
    private SyllabusRepository syllabusRepository;

    @Autowired
    private SyllabusAssessmentService syllabusAssessmentService;

    @Test
    void allSyllabusDocuments_shouldProduceValidAssessmentTables() {
        List<SyllabusDocument> allDocs = syllabusRepository.findByIdTerm("Winter 2026");
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
