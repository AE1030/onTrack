package org.tracker.gpatracker.assessmenttable.model;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.assessmenttable.repository.AssessmentTableDocumentRepository;
import org.tracker.gpatracker.support.ContainerIntegrationBase;
import org.tracker.gpatracker.syllabus.model.AssessmentItem;
import org.tracker.gpatracker.syllabus.model.Assessments;
import org.tracker.gpatracker.syllabus.model.CourseTermDocId;
import org.tracker.gpatracker.syllabus.model.GradingScheme;
import org.tracker.gpatracker.syllabus.model.ReplacementRule;
import org.tracker.gpatracker.syllabus.model.SchemeDefinition;
import org.tracker.gpatracker.syllabus.model.SelectionRule;
import org.tracker.gpatracker.syllabus.model.SyllabusDocument;
import org.tracker.gpatracker.syllabus.repository.SyllabusRepository;
import org.tracker.gpatracker.tenancy.UserContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the jsonb columns actually contain, and what comes back out of them.
 *
 * <p>These payloads were Mongo subdocuments until V16, and three of the mechanisms that made that
 * work do not carry over to Jackson. Each one fails silently rather than loudly, and none of the
 * existing tests would notice, because {@code AssessmentTableServiceTest} mocks the repository and
 * never reaches a converter:
 *
 * <ol>
 *   <li>Grades were encrypted by a Spring Data {@code @ValueConverter}. Jackson does not read that
 *       annotation, so a naive mapping writes every student's marks in plaintext.
 *   <li>{@code firstGradedAt}, {@code lastGradedAt} and {@code dueDateChangeCount} are
 *       {@code READ_ONLY}, which also means "ignore on deserialise". A default mapper drops all
 *       three on every load, and {@code GradeStamper}'s carry-forward quietly resets.
 *   <li>The syllabus payload's key names came from Mongo {@code @Field} annotations. Jackson would
 *       look for {@code dueDate} where the extractor writes {@code due_date}, and deserialise the
 *       whole extraction as nulls where it matters.
 * </ol>
 *
 * <p>So these assertions read the stored JSON with a native query, not just the mapped object. A
 * round-trip test alone would pass on a plaintext column.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JsonbPayloadRoundTripTest extends ContainerIntegrationBase {

    @Autowired
    private EntityManager em;

    @Autowired
    private StudentRepo studentRepo;

    @Autowired
    private AssessmentTableDocumentRepository tables;

    @Autowired
    private SyllabusRepository syllabuses;

    private Long student;

    @BeforeEach
    void seed() {
        student = UserContext.callAsSystem(() -> studentRepo.save(new Student()).getId());
        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ------------------------------------------------------------ assessment schemes

    @Test
    @DisplayName("a grade survives the round trip and is ciphertext in the column")
    void gradeIsEncryptedAtRestAndDecryptsOnLoad() {
        String id = UserContext.callAs(null, student, () -> {
            AssessmentTableDocument table = table(assessment("Midterm", new BigDecimal("87.50")));
            return tables.save(table).getId();
        });
        em.flush();
        em.clear();

        // What came back through the mapping.
        AssessmentTableDocument loaded = UserContext.callAs(null, student,
                () -> tables.findById(id).orElseThrow());
        BigDecimal grade = loaded.getSchemes().get(0).getAssessments().get(0).getGrade();
        assertThat(grade)
                .as("the grade must decrypt back to exactly what was written")
                .isEqualByComparingTo("87.50");

        // What is actually on disk. This is the assertion a round-trip test would miss.
        String storedGrade = (String) em.createNativeQuery(
                        "select schemes -> 0 -> 'assessments' -> 0 ->> 'grade' "
                                + "from assessment_table where id = :id")
                .setParameter("id", id)
                .getSingleResult();

        assertThat(storedGrade)
                .as("the stored grade must not be the plaintext number")
                .isNotEqualTo("87.50")
                .isNotEqualTo("87.5");
        assertThat(storedGrade)
                .as("AES-GCM output is base64 of a 12-byte IV plus ciphertext plus a 16-byte tag, "
                        + "so it is comfortably longer than the number it replaced")
                .hasSizeGreaterThan(24);
        assertThat(java.util.Base64.getDecoder().decode(storedGrade))
                .as("and it must be valid base64 of at least IV + tag")
                .hasSizeGreaterThan(28);
    }

    @Test
    @DisplayName("a null grade stays null rather than becoming ciphertext")
    void nullGradeIsLeftAlone() {
        String id = UserContext.callAs(null, student, () -> {
            AssessmentTableDocument table = table(assessment("Ungraded", null));
            return tables.save(table).getId();
        });
        em.flush();
        em.clear();

        AssessmentTableDocument loaded = UserContext.callAs(null, student,
                () -> tables.findById(id).orElseThrow());
        assertThat(loaded.getSchemes().get(0).getAssessments().get(0).getGrade()).isNull();
    }

    @Test
    @DisplayName("READ_ONLY evidence fields survive a load")
    void serverSetEvidenceIsNotDroppedOnRead() {
        // Truncated to millis: Postgres timestamptz keeps microseconds, and the point here is that
        // the values come back at all, not that the JSON preserves nanosecond precision.
        Instant first = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant last = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        String id = UserContext.callAs(null, student, () -> {
            SchemeAssessment a = assessment("Lab 1", new BigDecimal("72"));
            a.setFirstGradedAt(first);
            a.setLastGradedAt(last);
            a.setDueDateChangeCount(4);
            return tables.save(table(a)).getId();
        });
        em.flush();
        em.clear();

        AssessmentTableDocument loaded = UserContext.callAs(null, student,
                () -> tables.findById(id).orElseThrow());
        SchemeAssessment back = loaded.getSchemes().get(0).getAssessments().get(0);

        assertThat(back.getFirstGradedAt())
                .as("dropped here means GradeStamper loses when a grade first appeared, on every save")
                .isEqualTo(first);
        assertThat(back.getLastGradedAt()).isEqualTo(last);
        assertThat(back.getDueDateChangeCount())
                .as("dropped here means the leaderboard's due-date-change flag silently reads zero")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("an empty scheme list and a null payload both round-trip")
    void degenerateShapesAreTolerated() {
        String emptyId = UserContext.callAs(null, student, () -> {
            AssessmentTableDocument t = new AssessmentTableDocument();
            t.setOwnerId(student);
            t.setCourseCode("EMPTY 1A00");
            t.setTerm("Winter 2026");
            t.setSchemes(List.of());
            return tables.save(t).getId();
        });
        String nullId = UserContext.callAs(null, student, () -> {
            AssessmentTableDocument t = new AssessmentTableDocument();
            t.setOwnerId(student);
            t.setCourseCode("NULL 1A00");
            t.setTerm("Winter 2026");
            t.setSchemes(null);
            return tables.save(t).getId();
        });
        em.flush();
        em.clear();

        UserContext.runAs(null, student, () -> {
            assertThat(tables.findById(emptyId).orElseThrow().getSchemes()).isEmpty();
            assertThat(tables.findById(nullId).orElseThrow().getSchemes()).isNull();
        });
    }

    // ------------------------------------------------------------ syllabus payload

    @Test
    @DisplayName("the syllabus payload is stored in snake_case, matching the Python pipeline")
    void syllabusKeysStaySnakeCase() {
        CourseTermDocId id = new CourseTermDocId("BIOLOGY 3AA3", "Winter 2026", "doc-abc-123");
        UserContext.runAsSystem(() -> {
            SyllabusDocument doc = new SyllabusDocument();
            doc.setId(id);
            doc.setAssessments(sampleAssessments());
            syllabuses.save(doc);
        });
        em.flush();
        em.clear();

        // jsonb_object_keys on the nested objects, so a renamed key shows up as a missing key
        // rather than as a null value somewhere downstream.
        @SuppressWarnings("unchecked")
        List<String> topKeys = em.createNativeQuery(
                        "select jsonb_object_keys(assessments) from syllabus where doc_code = :d")
                .setParameter("d", "doc-abc-123")
                .getResultList();
        assertThat(topKeys)
                .as("the extractor and GeminiSyllabusExtractionService both emit grading_scheme")
                .containsExactly("grading_scheme");

        String itemKeysJson = (String) em.createNativeQuery(
                        "select (assessments -> 'grading_scheme' -> 'schemes' -> 'scheme_1' "
                                + "-> 'assessments' -> 0)::text from syllabus where doc_code = :d")
                .setParameter("d", "doc-abc-123")
                .getSingleResult();

        assertThat(itemKeysJson)
                .as("these four were @Field(\"...\") on Mongo; SNAKE_CASE has to reproduce them")
                .contains("\"due_date\"")
                .contains("\"bonus_assessment\"")
                .contains("\"replacement_rule\"")
                .contains("\"start_time\"");
        assertThat(itemKeysJson)
                .as("no naming strategy turns \"n\" into \"N\", so ReplacementRule needs @JsonProperty")
                .contains("\"N\"");
        assertThat(itemKeysJson)
                .as("camelCase here would mean the pipeline's output deserialises as null")
                .doesNotContain("\"dueDate\"")
                .doesNotContain("\"bonusAssessment\"");

        String selectionRule = (String) em.createNativeQuery(
                        "select assessments -> 'grading_scheme' ->> 'selection_rule' "
                                + "from syllabus where doc_code = :d")
                .setParameter("d", "doc-abc-123")
                .getSingleResult();
        assertThat(selectionRule).isEqualTo("MAX");
    }

    @Test
    @DisplayName("the syllabus payload reads back into the same object graph")
    void syllabusPayloadRoundTrips() {
        CourseTermDocId id = new CourseTermDocId("CHEM 1AA3", "Winter 2026", "doc-xyz-789");
        UserContext.runAsSystem(() -> {
            SyllabusDocument doc = new SyllabusDocument();
            doc.setId(id);
            doc.setAssessments(sampleAssessments());
            syllabuses.save(doc);
        });
        em.flush();
        em.clear();

        SyllabusDocument loaded = UserContext.callAsSystem(() -> syllabuses.findById(id).orElseThrow());

        // The composite key doubles as the accessors the rest of the code programs against.
        assertThat(loaded.getCourseCode()).isEqualTo("CHEM 1AA3");
        assertThat(loaded.getTerm()).isEqualTo("Winter 2026");
        assertThat(loaded.getDocCode()).isEqualTo("doc-xyz-789");

        AssessmentItem item = loaded.getAssessments()
                .getGradingScheme()
                .getSchemes()
                .get("scheme_1")
                .getAssessmentItemList()
                .get(0);

        assertThat(item.getName()).isEqualTo("Final Exam");
        assertThat(item.getDueDate()).isEqualTo("2026-04-15");
        assertThat(item.getStartTime()).isEqualTo("09:00");
        assertThat(item.getWeight()).isEqualByComparingTo("0.40");
        assertThat(item.getBonusAssessment()).isFalse();
        assertThat(item.getReplacementRule()).isNotNull();
        assertThat(item.getReplacementRule().getN())
                .as("the drop-lowest-N rule reading zero would silently stop replacing anything")
                .isEqualTo(2);
        assertThat(item.getReplacementRule().getTriggerType()).isEqualTo("DROP_LOWEST");
    }

    // ------------------------------------------------------------ helpers

    private AssessmentTableDocument table(SchemeAssessment... assessments) {
        AssessmentScheme scheme = new AssessmentScheme();
        scheme.setSchemeName("Scheme 1");
        scheme.setAssessments(List.of(assessments));

        AssessmentTableDocument table = new AssessmentTableDocument();
        table.setOwnerId(student);
        table.setCourseCode("SFWRENG 2AA4");
        table.setTerm("Winter 2026");
        table.setSchemes(List.of(scheme));
        return table;
    }

    private static SchemeAssessment assessment(String name, BigDecimal grade) {
        SchemeAssessment a = new SchemeAssessment();
        a.setName(name);
        a.setWeight(new BigDecimal("0.25"));
        a.setDueDate("2026-03-01");
        a.setGrade(grade);
        return a;
    }

    private static Assessments sampleAssessments() {
        ReplacementRule rule = new ReplacementRule();
        rule.setTriggerType("DROP_LOWEST");
        rule.setN(2);

        AssessmentItem item = new AssessmentItem();
        item.setName("Final Exam");
        item.setCategory("exam");
        item.setDueDate("2026-04-15");
        item.setStartTime("09:00");
        item.setEndTime("12:00");
        item.setWeight(new BigDecimal("0.40"));
        item.setBonusAssessment(false);
        item.setReplacementRule(rule);

        SchemeDefinition definition = new SchemeDefinition();
        definition.setLabel("Standard");
        definition.setAssessmentItemList(List.of(item));

        GradingScheme gradingScheme = new GradingScheme();
        gradingScheme.setSchemes(Map.of("scheme_1", definition));
        gradingScheme.setSelectionRule(SelectionRule.MAX);

        Assessments assessments = new Assessments();
        assessments.setGradingScheme(gradingScheme);
        return assessments;
    }
}
