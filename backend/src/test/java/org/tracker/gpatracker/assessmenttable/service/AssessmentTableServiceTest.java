package org.tracker.gpatracker.assessmenttable.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.assessmenttable.dto.SaveAssessmentTableDTO;
import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.assessmenttable.model.SchemeAssessment;
import org.tracker.gpatracker.assessmenttable.repository.AssessmentTableDocumentRepository;
import org.tracker.gpatracker.syllabus.dto.AssessmentTableDTO;
import org.tracker.gpatracker.syllabus.model.SyllabusDocument;
import org.tracker.gpatracker.syllabus.service.SyllabusAssessmentService;
import org.tracker.gpatracker.terms.TermService;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock
    private TermService termService;

    private AssessmentTableService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AssessmentTableService(repository, studentService,
                syllabusAssessmentService, eventPublisher, termService);
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

        when(repository.findByOwnerIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
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
        when(repository.findByOwnerIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
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
        when(repository.findByOwnerIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
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
        when(repository.findByOwnerIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
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
        when(repository.findByOwnerIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
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

    /**
     * The write target must come from a document this student already owns, never from the request.
     *
     * <p>{@code save()} upserts by {@code _id}, so honouring a client-supplied id let any caller
     * name — and overwrite — another student's assessment table. A stamping listener cannot catch
     * this: the row is chosen before ownership is ever consulted, and the document written carries
     * the attacker's own owner quite legitimately.
     */
    @Test
    void saveToRepo_ignoresClientSuppliedId_whenNoDocumentExists() {
        Long studentId = 1L;
        when(studentService.getStudentID()).thenReturn(studentId);
        when(repository.findByOwnerIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
                .thenReturn(Optional.empty());
        when(repository.save(any(AssessmentTableDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        SaveAssessmentTableDTO dto = new SaveAssessmentTableDTO();
        dto.setCourseCode("COMP101");
        dto.setTerm("Winter 2026");
        dto.setId("id-of-another-students-table");
        dto.setSchemes(List.of());

        service.saveToRepo(dto);

        ArgumentCaptor<AssessmentTableDocument> captor =
                ArgumentCaptor.forClass(AssessmentTableDocument.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getId())
                .as("a null id makes this an insert; anything else is an upsert onto a chosen row")
                .isNull();
        assertThat(captor.getValue().getOwnerId()).isEqualTo(studentId);
    }

    @Test
    void saveToRepo_usesTheCallersOwnDocumentId_notTheOneInTheRequest() {
        Long studentId = 1L;
        when(studentService.getStudentID()).thenReturn(studentId);

        AssessmentTableDocument existing = new AssessmentTableDocument();
        existing.setId("the-callers-own-table");
        when(repository.findByOwnerIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(AssessmentTableDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        SaveAssessmentTableDTO dto = new SaveAssessmentTableDTO();
        dto.setCourseCode("COMP101");
        dto.setTerm("Winter 2026");
        dto.setId("id-of-another-students-table");
        dto.setSchemes(List.of());

        service.saveToRepo(dto);

        ArgumentCaptor<AssessmentTableDocument> captor =
                ArgumentCaptor.forClass(AssessmentTableDocument.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getId()).isEqualTo("the-callers-own-table");
    }

    // ------------------------------------------------------- past terms are view only

    @Test
    void saveTableAndGrade_pastTerm_refusedBeforeEitherStoreIsTouched() {
        // The guard is the contract; the greyed-out UI only decorates it. A status-code check
        // alone would still pass if the guard ran after the table write, so this asserts that
        // neither the table nor the grade saw anything.
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Past terms are view only"))
                .when(termService).requireEditable("Fall 2025");

        SaveAssessmentTableDTO dto = new SaveAssessmentTableDTO();
        dto.setCourseCode("COMP101");
        dto.setTerm("Fall 2025");
        dto.setSchemes(List.of());
        dto.setGrade(new BigDecimal("88"));

        assertThatThrownBy(() -> service.saveTableAndGrade(dto))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(repository, never()).save(any(AssessmentTableDocument.class));
        verify(studentService, never()).updateCourseGrade(anyString(), anyString(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void saveTableAndGrade_currentTerm_forwardsTheTermToTheGradeWrite() {
        Long studentId = 1L;
        when(studentService.getStudentID()).thenReturn(studentId);
        when(repository.findByOwnerIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
                .thenReturn(Optional.empty());
        when(repository.save(any(AssessmentTableDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        SaveAssessmentTableDTO dto = new SaveAssessmentTableDTO();
        dto.setCourseCode("COMP101");
        dto.setTerm("Winter 2026");
        dto.setSchemes(List.of());
        dto.setGrade(new BigDecimal("88"));

        service.saveTableAndGrade(dto);

        // Without the term the enrollment lookup would resolve to whichever row it found first.
        verify(studentService).updateCourseGrade("COMP101", "Winter 2026", new BigDecimal("88"));
    }

    // ------------------------------------------------- the unique index's losing write

    @Test
    void saveToRepo_duplicateKey_mergesOntoTheDocumentThatWon() {
        Long studentId = 1L;
        when(studentService.getStudentID()).thenReturn(studentId);

        AssessmentTableDocument winner = new AssessmentTableDocument();
        winner.setId("the-winning-insert");

        // First read finds nothing, so this request inserts; the insert loses the race and the
        // unique index rejects it; the second read now finds the document that won.
        when(repository.findByOwnerIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.save(any(AssessmentTableDocument.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key"))
                .thenAnswer(inv -> inv.getArgument(0));

        SaveAssessmentTableDTO dto = new SaveAssessmentTableDTO();
        dto.setCourseCode("COMP101");
        dto.setTerm("Winter 2026");
        dto.setSchemes(List.of());

        AssessmentTableDocument saved = service.saveToRepo(dto);

        assertThat(saved.getId())
                .as("the retry must be an update onto the winner, not a second insert")
                .isEqualTo("the-winning-insert");
        verify(repository, times(2)).save(any(AssessmentTableDocument.class));
    }

    @Test
    void saveToRepo_duplicateKeyWithNothingToMergeOnto_rethrows() {
        Long studentId = 1L;
        when(studentService.getStudentID()).thenReturn(studentId);
        when(repository.findByOwnerIdAndCourseCodeAndTerm(studentId, "COMP101", "Winter 2026"))
                .thenReturn(Optional.empty());
        when(repository.save(any(AssessmentTableDocument.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key"));

        SaveAssessmentTableDTO dto = new SaveAssessmentTableDTO();
        dto.setCourseCode("COMP101");
        dto.setTerm("Winter 2026");
        dto.setSchemes(List.of());

        // Retry once, not in a loop: a duplicate with no winner to find is not a race, and
        // looping would hide whatever it actually is.
        assertThatThrownBy(() -> service.saveToRepo(dto))
                .isInstanceOf(DuplicateKeyException.class);
        verify(repository, times(1)).save(any(AssessmentTableDocument.class));
    }
}
