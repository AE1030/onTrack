package org.tracker.gpatracker.syllabus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tracker.gpatracker.syllabus.dto.AssessmentTableDTO;
import org.tracker.gpatracker.syllabus.model.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SyllabusAssessmentServiceTest {

    private SyllabusAssessmentService service;

    @BeforeEach
    void setUp() {
        // SyllabusAssessmentService only uses providers and studentService in getSyllabusDocument(),
        // not in getNormalizedAssessmentItems(), so we can pass empty/null for unit testing normalization.
        service = new SyllabusAssessmentService(List.of(), null);
    }

    // ==================== Helper builders ====================

    private SyllabusDocument buildDocument(Map<String, SchemeDefinition> schemes) {
        GradingScheme gradingScheme = new GradingScheme();
        gradingScheme.setSchemes(schemes);

        Assessments assessments = new Assessments();
        assessments.setGradingScheme(gradingScheme);

        SyllabusDocument doc = new SyllabusDocument();
        doc.setAssessments(assessments);
        doc.setCourseCode("TEST101");
        doc.setTerm("Winter 2026");
        return doc;
    }

    private AssessmentItem buildItem(String name, String category,
                                     BigDecimal weight, Integer occurrences) {
        AssessmentItem item = new AssessmentItem();
        item.setName(name);
        item.setCategory(category);
        item.setWeight(weight);
        if (occurrences != null) {
            Occurrence occ = new Occurrence();
            occ.setTotal(occurrences);
            item.setOccurrence(occ);
        }
        return item;
    }

    private SchemeDefinition buildScheme(String label, List<AssessmentItem> items) {
        SchemeDefinition scheme = new SchemeDefinition();
        scheme.setLabel(label);
        scheme.setAssessmentItemList(items);
        return scheme;
    }

    // ==================== Null/empty edge cases ====================

    @Test
    void getNormalizedAssessmentItems_nullAssessments_returnsEmptyMap() {
        SyllabusDocument doc = new SyllabusDocument();
        doc.setAssessments(null);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        assertThat(result).isEmpty();
    }

    @Test
    void getNormalizedAssessmentItems_nullGradingScheme_returnsEmptyMap() {
        Assessments assessments = new Assessments();
        assessments.setGradingScheme(null);
        SyllabusDocument doc = new SyllabusDocument();
        doc.setAssessments(assessments);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        assertThat(result).isEmpty();
    }

    @Test
    void getNormalizedAssessmentItems_nullSchemes_returnsEmptyMap() {
        GradingScheme gradingScheme = new GradingScheme();
        gradingScheme.setSchemes(null);
        Assessments assessments = new Assessments();
        assessments.setGradingScheme(gradingScheme);
        SyllabusDocument doc = new SyllabusDocument();
        doc.setAssessments(assessments);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        assertThat(result).isEmpty();
    }

    @Test
    void getNormalizedAssessmentItems_emptySchemes_returnsEmptyMap() {
        SyllabusDocument doc = buildDocument(new LinkedHashMap<>());

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        assertThat(result).isEmpty();
    }

    @Test
    void getNormalizedAssessmentItems_schemeWithNullItems_skipsScheme() {
        SchemeDefinition scheme = new SchemeDefinition();
        scheme.setLabel("Empty Scheme");
        scheme.setAssessmentItemList(null);

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("scheme_1", scheme);
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        assertThat(result).isEmpty();
    }

    // ==================== Single item, no occurrence ====================

    @Test
    void singleAssessment_noOccurrence_returnsSingleDto() {
        AssessmentItem item = buildItem("Final Exam", "final_exam",
                new BigDecimal("40"), null);
        item.setDueDate("2026-04-15");
        item.setStartTime("09:00");
        item.setEndTime("12:00");
        item.setLocation("Gym A");

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("scheme_1", buildScheme("Standard", List.of(item)));
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        assertThat(result).containsKey("Standard");
        List<AssessmentTableDTO> dtos = result.get("Standard");
        assertThat(dtos).hasSize(1);

        AssessmentTableDTO dto = dtos.get(0);
        assertThat(dto.getAssessmentName()).isEqualTo("Final Exam");
        assertThat(dto.getWeights()).containsExactly(new BigDecimal("40"));
        assertThat(dto.getDueDate()).isEqualTo("2026-04-15");
        assertThat(dto.getStartTime()).isEqualTo("09:00");
        assertThat(dto.getEndTime()).isEqualTo("12:00");
        assertThat(dto.getLocation()).isEqualTo("Gym A");
    }

    // ==================== Occurrence expansion ====================

    @Test
    void multipleOccurrences_expandsAndSplitsWeight() {
        AssessmentItem item = buildItem("Assignment", "assignment",
                new BigDecimal("30"), 3);

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("scheme_1", buildScheme("Standard", List.of(item)));
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        List<AssessmentTableDTO> dtos = result.get("Standard");
        assertThat(dtos).hasSize(3);

        BigDecimal expectedWeight = new BigDecimal("30")
                .divide(BigDecimal.valueOf(3), 10, RoundingMode.HALF_UP);

        for (int i = 0; i < 3; i++) {
            assertThat(dtos.get(i).getAssessmentName()).isEqualTo("Assignment #" + (i + 1));
            assertThat(dtos.get(i).getWeights()).hasSize(1);
            assertThat(dtos.get(i).getWeights()[0]).isEqualByComparingTo(expectedWeight);
        }
    }

    @Test
    void occurrenceWithDueDateList_assignsDatesToEachOccurrence() {
        AssessmentItem item = buildItem("Quiz", "quiz",
                new BigDecimal("20"), 3);
        item.setDueDate("2026-01-15, 2026-02-15, 2026-03-15");

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("scheme_1", buildScheme("Standard", List.of(item)));
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        List<AssessmentTableDTO> dtos = result.get("Standard");
        assertThat(dtos).hasSize(3);
        assertThat(dtos.get(0).getDueDate()).isEqualTo("2026-01-15");
        assertThat(dtos.get(1).getDueDate()).isEqualTo("2026-02-15");
        assertThat(dtos.get(2).getDueDate()).isEqualTo("2026-03-15");
    }

    @Test
    void occurrenceWithFewerDueDatesThanTotal_fallsBackToOriginalDueDate() {
        AssessmentItem item = buildItem("Lab", "lab",
                new BigDecimal("10"), 3);
        item.setDueDate("2026-01-20");

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("scheme_1", buildScheme("Standard", List.of(item)));
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        List<AssessmentTableDTO> dtos = result.get("Standard");
        assertThat(dtos.get(0).getDueDate()).isEqualTo("2026-01-20");
        // Items beyond the split dates fall back to the original dueDate string
        assertThat(dtos.get(1).getDueDate()).isEqualTo("2026-01-20");
        assertThat(dtos.get(2).getDueDate()).isEqualTo("2026-01-20");
    }

    // ==================== Lowest-N-dropped ====================

    @Test
    void lowestNDropped_setsCorrectWeightsAndN() {
        AssessmentItem item = buildItem("Tutorial", "tutorial",
                new BigDecimal("20"), 10);
        ReplacementRule rule = new ReplacementRule();
        rule.setTriggerType("LOWEST_N_DROPPED");
        rule.setN(2);
        item.setReplacementRule(rule);

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("scheme_1", buildScheme("Standard", List.of(item)));
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        List<AssessmentTableDTO> dtos = result.get("Standard");
        assertThat(dtos).hasSize(10);

        // Each kept item weight = 20 / (10 - 2) = 2.5
        BigDecimal expectedKeptWeight = new BigDecimal("20")
                .divide(BigDecimal.valueOf(8), 10, RoundingMode.HALF_UP);

        for (AssessmentTableDTO dto : dtos) {
            assertThat(dto.getWeights()).hasSize(2);
            assertThat(dto.getWeights()[0]).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(dto.getWeights()[1]).isEqualByComparingTo(expectedKeptWeight);
            assertThat(dto.getN()).isEqualTo(2);
        }
    }

    // ==================== Scheme label fallback ====================

    @Test
    void schemeWithNullLabel_usesSchemeKey() {
        AssessmentItem item = buildItem("Midterm", "midterm",
                new BigDecimal("25"), null);

        SchemeDefinition scheme = new SchemeDefinition();
        scheme.setLabel(null);
        scheme.setAssessmentItemList(List.of(item));

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("my_scheme_key", scheme);
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        assertThat(result).containsKey("my_scheme_key");
    }

    @Test
    void schemeWithBlankLabel_usesSchemeKey() {
        AssessmentItem item = buildItem("Test", "test",
                new BigDecimal("15"), null);

        SchemeDefinition scheme = new SchemeDefinition();
        scheme.setLabel("   ");
        scheme.setAssessmentItemList(List.of(item));

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("fallback_key", scheme);
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        assertThat(result).containsKey("fallback_key");
    }

    // ==================== Name fallback ====================

    @Test
    void itemWithNullName_fallsBackToCategory() {
        AssessmentItem item = buildItem(null, "quiz",
                new BigDecimal("10"), null);

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("scheme_1", buildScheme("Standard", List.of(item)));
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        assertThat(result.get("Standard").get(0).getAssessmentName()).isEqualTo("quiz");
    }

    @Test
    void itemWithNullNameAndNullCategory_fallsBackToAssessment() {
        AssessmentItem item = buildItem(null, null, new BigDecimal("10"), null);

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("scheme_1", buildScheme("Standard", List.of(item)));
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        assertThat(result.get("Standard").get(0).getAssessmentName()).isEqualTo("Assessment");
    }

    // ==================== Multiple schemes ====================

    @Test
    void multipleSchemes_allExpandedIndependently() {
        AssessmentItem exam = buildItem("Final Exam", "final_exam",
                new BigDecimal("50"), null);
        AssessmentItem assignment = buildItem("Assignment", "assignment",
                new BigDecimal("30"), 3);
        AssessmentItem participation = buildItem("Participation", "participation",
                new BigDecimal("20"), null);

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("scheme_a", buildScheme("Scheme A", List.of(exam, assignment)));
        schemes.put("scheme_b", buildScheme("Scheme B", List.of(exam, participation)));
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        assertThat(result).hasSize(2);
        // Scheme A: 1 exam + 3 assignments = 4
        assertThat(result.get("Scheme A")).hasSize(4);
        // Scheme B: 1 exam + 1 participation = 2
        assertThat(result.get("Scheme B")).hasSize(2);
    }

    // ==================== Null weight ====================

    @Test
    void itemWithNullWeight_setsNullInDto() {
        AssessmentItem item = buildItem("Bonus Work", "bonus", null, null);

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("scheme_1", buildScheme("Standard", List.of(item)));
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        AssessmentTableDTO dto = result.get("Standard").get(0);
        assertThat(dto.getWeights()).containsExactly((BigDecimal) null);
    }

    // ==================== Occurrence edge cases ====================

    @Test
    void occurrenceWithZeroTotal_treatedAsSingle() {
        AssessmentItem item = buildItem("Project", "project",
                new BigDecimal("25"), 0);

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("scheme_1", buildScheme("Standard", List.of(item)));
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        List<AssessmentTableDTO> dtos = result.get("Standard");
        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).getAssessmentName()).isEqualTo("Project");
    }

    @Test
    void occurrenceWithNegativeTotal_treatedAsSingle() {
        AssessmentItem item = buildItem("Report", "report",
                new BigDecimal("15"), -1);

        Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        schemes.put("scheme_1", buildScheme("Standard", List.of(item)));
        SyllabusDocument doc = buildDocument(schemes);

        Map<String, List<AssessmentTableDTO>> result = service.getNormalizedAssessmentItems(doc);

        assertThat(result.get("Standard")).hasSize(1);
        assertThat(result.get("Standard").get(0).getAssessmentName()).isEqualTo("Report");
    }
}
