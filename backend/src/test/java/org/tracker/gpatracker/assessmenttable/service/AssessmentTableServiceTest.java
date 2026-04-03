package org.tracker.gpatracker.assessmenttable.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.assessmenttable.model.SchemeAssessment;
import org.tracker.gpatracker.assessmenttable.repository.AssessmentTableDocumentRepository;
import org.tracker.gpatracker.syllabus.dto.AssessmentTableDTO;
import org.tracker.gpatracker.syllabus.model.SyllabusDocument;
import org.tracker.gpatracker.syllabus.service.SyllabusAssessmentService;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssessmentTableServiceTest {

    @Mock
    private AssessmentTableDocumentRepository repository;
    @Mock
    private StudentService studentService;
    @Mock
    private SyllabusAssessmentService syllabusAssessmentService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AssessmentTableService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AssessmentTableService(repository, studentService,
                syllabusAssessmentService, eventPublisher);
        // Set the currentTerm field via reflection since @Value won't work in unit tests
        Field currentTermField = AssessmentTableService.class.getDeclaredField("currentTerm");
        currentTermField.setAccessible(true);
        currentTermField.set(service, "Winter 2026");
    }

    @Test
    void getByStudentIdAndCourseCodeAndTerm_foundInRepo_returnsDtoMap() {
        Long studentId = 1L;
        when(studentService.getStudentID()).thenReturn(studentId);

        SchemeAssessment assessment = new SchemeAssessment();
        assessment.setName("Midterm");
        assessment.setWeight(new BigDecimal("30"));

        AssessmentScheme scheme = new AssessmentScheme();
        scheme.setSchemeName("Standard");
        scheme.setAssessments(List.of(assessment));

        AssessmentTableDocument doc = new AssessmentTableDocument();
        doc.setSchemes(List.of(scheme));

        when(repository.findByStudentIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
                .thenReturn(Optional.of(doc));

        Map<String, List<AssessmentTableDTO>> result =
                service.getByStudentIdAndCourseCodeAndTerm("COMP101", "Winter 2026");

        assertThat(result).containsKey("Standard");
        assertThat(result.get("Standard")).hasSize(1);
        assertThat(result.get("Standard").get(0).getAssessmentName()).isEqualTo("Midterm");
        verify(syllabusAssessmentService, never()).getSyllabusDocument(anyString(), anyString());
    }

    @Test
    void getByStudentIdAndCourseCodeAndTerm_notInRepo_fallsBackToSyllabus() {
        Long studentId = 1L;
        when(studentService.getStudentID()).thenReturn(studentId);
        when(repository.findByStudentIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
                .thenReturn(Optional.empty());

        SyllabusDocument syllabusDoc = new SyllabusDocument();
        when(syllabusAssessmentService.getSyllabusDocument("COMP101", "Winter 2026"))
                .thenReturn(syllabusDoc);

        Map<String, List<AssessmentTableDTO>> normalizedResult = new LinkedHashMap<>();
        AssessmentTableDTO dto = new AssessmentTableDTO();
        dto.setAssessmentName("Final Exam");
        normalizedResult.put("Standard", List.of(dto));
        when(syllabusAssessmentService.getNormalizedAssessmentItems(syllabusDoc))
                .thenReturn(normalizedResult);

        Map<String, List<AssessmentTableDTO>> result =
                service.getByStudentIdAndCourseCodeAndTerm("COMP101", "Winter 2026");

        assertThat(result).containsKey("Standard");
        assertThat(result.get("Standard").get(0).getAssessmentName()).isEqualTo("Final Exam");
    }

    @Test
    void getByStudentIdAndCourseCodeAndTerm_syllabusNotFound_throws404() {
        Long studentId = 1L;
        when(studentService.getStudentID()).thenReturn(studentId);
        when(repository.findByStudentIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
                .thenReturn(Optional.empty());
        when(syllabusAssessmentService.getSyllabusDocument("COMP101", "Winter 2026"))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "Syllabus not found"));

        assertThatThrownBy(() ->
                service.getByStudentIdAndCourseCodeAndTerm("COMP101", "Winter 2026"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void getByStudentIdAndCourseCodeAndTerm_syllabusReturnsNull_throws404() {
        Long studentId = 1L;
        when(studentService.getStudentID()).thenReturn(studentId);
        when(repository.findByStudentIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
                .thenReturn(Optional.empty());
        when(syllabusAssessmentService.getSyllabusDocument("COMP101", "Winter 2026"))
                .thenReturn(null);

        assertThatThrownBy(() ->
                service.getByStudentIdAndCourseCodeAndTerm("COMP101", "Winter 2026"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void getByStudentIdAndCourseCodeAndTerm_repoDocWithEmptySchemes_fallsBackToSyllabus() {
        Long studentId = 1L;
        when(studentService.getStudentID()).thenReturn(studentId);

        // Document exists but has no schemes -> toAssessmentTableMap returns empty
        AssessmentTableDocument doc = new AssessmentTableDocument();
        doc.setSchemes(List.of());
        when(repository.findByStudentIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
                .thenReturn(Optional.of(doc));

        SyllabusDocument syllabusDoc = new SyllabusDocument();
        when(syllabusAssessmentService.getSyllabusDocument("COMP101", "Winter 2026"))
                .thenReturn(syllabusDoc);

        Map<String, List<AssessmentTableDTO>> normalizedResult = new LinkedHashMap<>();
        normalizedResult.put("Fallback", List.of(new AssessmentTableDTO()));
        when(syllabusAssessmentService.getNormalizedAssessmentItems(syllabusDoc))
                .thenReturn(normalizedResult);

        Map<String, List<AssessmentTableDTO>> result =
                service.getByStudentIdAndCourseCodeAndTerm("COMP101", "Winter 2026");

        assertThat(result).containsKey("Fallback");
    }
}
