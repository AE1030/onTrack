package org.tracker.gpatracker.assessmenttable.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.assessmenttable.dto.SaveAssessmentTableDTO;
import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.assessmenttable.model.SchemeAssessment;
import org.tracker.gpatracker.assessmenttable.repository.AssessmentTableDocumentRepository;
import org.tracker.gpatracker.syllabus.dto.AssessmentTableDTO;
import org.tracker.gpatracker.syllabus.model.AbstractSyllabusDocument;
import org.tracker.gpatracker.calendar.event.AssessmentTableUpdatedEvent;
import org.tracker.gpatracker.syllabus.service.SyllabusAssessmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AssessmentTableService {

    private static final Logger logger = LoggerFactory.getLogger(AssessmentTableService.class);

    private final AssessmentTableDocumentRepository repository;
    private final StudentService studentService;
    private final SyllabusAssessmentService syllabusAssessmentService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${app.current-term}")
    private String currentTerm;

    public AssessmentTableService(AssessmentTableDocumentRepository repository,
                                   StudentService studentService,
                                   SyllabusAssessmentService syllabusAssessmentService,
                                   ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.studentService = studentService;
        this.syllabusAssessmentService = syllabusAssessmentService;
        this.eventPublisher = eventPublisher;
    }

    public AssessmentTableDocument saveToRepo(SaveAssessmentTableDTO dto) {
        Long studentId = studentService.getStudentID();
        AssessmentTableDocument document = new AssessmentTableDocument();
        document.setCourseCode(dto.getCourseCode());
        document.setTerm(currentTerm);
        document.setSchemes(dto.getSchemes());
        document.setStudentId(studentId);

        // The write target is chosen only from a document this student already owns. Honouring
        // dto.getId() let a caller name any _id, and save() upserts by _id -- so another student's
        // assessment table could be overwritten. Leaving the id null makes this an insert instead.
        // A stamping listener cannot catch this, because the row being written is selected before
        // ownership is ever considered.
        repository.findByStudentIdAndCourseCodeAndTerm(studentId, dto.getCourseCode(), currentTerm)
                .ifPresent(existing -> document.setId(existing.getId()));
        AssessmentTableDocument saved = repository.save(document);
        eventPublisher.publishEvent(new AssessmentTableUpdatedEvent(this, studentId));
        return saved;
    }

    public void deleteByCourseCodeAndTerm(Long studentId, String courseCode, String term) {
        repository.deleteByStudentIdAndCourseCodeAndTerm(studentId, courseCode, term);
        eventPublisher.publishEvent(new AssessmentTableUpdatedEvent(this, studentId));
    }

    public void refreshFromSyllabus(String courseCode) {
        Long studentId = studentService.getStudentID();
        AbstractSyllabusDocument doc;
        try {
            doc = syllabusAssessmentService.getSyllabusDocument(courseCode, currentTerm);
        } catch (Exception e) {
            return;
        }
        Map<String, List<AssessmentTableDTO>> items = syllabusAssessmentService.getNormalizedAssessmentItems(doc);
        if (items.isEmpty()) {
            return;
        }
        SaveAssessmentTableDTO dto = toSaveDTO(courseCode, items);
        saveToRepo(dto);
    }

    public void createFromSyllabus(String courseCode) {
        Long studentId = studentService.getStudentID();

        AbstractSyllabusDocument doc;
        try {
            doc = syllabusAssessmentService.getSyllabusDocument(courseCode, currentTerm);
        } catch (Exception e) {
            return;
        }

        if (repository.findByStudentIdAndCourseCodeAndTerm(studentId, courseCode, currentTerm).isPresent()) {
            return;
        }

        Map<String, List<AssessmentTableDTO>> items = syllabusAssessmentService.getNormalizedAssessmentItems(doc);
        if (items.isEmpty()) {
            return;
        }

        SaveAssessmentTableDTO dto = toSaveDTO(courseCode, items);
        saveToRepo(dto);
    }

    private SaveAssessmentTableDTO toSaveDTO(String courseCode, Map<String, List<AssessmentTableDTO>> items) {
        SaveAssessmentTableDTO dto = new SaveAssessmentTableDTO();
        dto.setCourseCode(courseCode);

        List<AssessmentScheme> schemes = new ArrayList<>();
        for (Map.Entry<String, List<AssessmentTableDTO>> entry : items.entrySet()) {
            AssessmentScheme scheme = new AssessmentScheme();
            scheme.setSchemeName(entry.getKey());

            List<SchemeAssessment> assessments = new ArrayList<>();
            for (AssessmentTableDTO item : entry.getValue()) {
                SchemeAssessment sa = new SchemeAssessment();
                sa.setName(item.getAssessmentName());
                sa.setDueDate(item.getDueDate());
                sa.setStartTime(item.getStartTime());
                sa.setEndTime(item.getEndTime());
                sa.setLocation(item.getLocation());
                BigDecimal[] weights = item.getWeights();
                sa.setWeight(weights != null && weights.length > 0 ? weights[0] : null);
                assessments.add(sa);
            }
            scheme.setAssessments(assessments);
            schemes.add(scheme);
        }

        dto.setSchemes(schemes);
        return dto;
    }

    public Map<String, List<AssessmentTableDTO>> getByStudentIdAndCourseCodeAndTerm(String courseCode, String term) {
        Long studentId = studentService.getStudentID();

        Map<String, List<AssessmentTableDTO>> result = repository
                .findByStudentIdAndCourseCodeAndTerm(studentId, courseCode, term)
                .map(this::toAssessmentTableMap)
                .orElse(Map.of());

        if (!result.isEmpty()) {
            return result;
        }

        AbstractSyllabusDocument syllabusDocument;
        try {
            syllabusDocument = syllabusAssessmentService.getSyllabusDocument(courseCode, term);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Assessment table not found"
            );
        } catch (ResponseStatusException ex) {
            throw ex;
        }

        if (syllabusDocument == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Assessment table not found"
            );
        }

        return syllabusAssessmentService.getNormalizedAssessmentItems(syllabusDocument);
    }

    private Map<String, List<AssessmentTableDTO>> toAssessmentTableMap(AssessmentTableDocument document) {
        Map<String, List<AssessmentTableDTO>> result = new LinkedHashMap<>();
        List<AssessmentScheme> schemes = document.getSchemes();
        if (schemes == null || schemes.isEmpty()) {
            return result;
        }
        int index = 1;
        for (AssessmentScheme scheme : schemes) {
            String schemeName = scheme == null ? null : scheme.getSchemeName();
            if (schemeName == null || schemeName.isBlank()) {
                schemeName = "Scheme " + index;
            }
            result.put(schemeName, toAssessmentTableItems(scheme == null ? null : scheme.getAssessments()));
            index++;
        }
        return result;
    }

    private List<AssessmentTableDTO> toAssessmentTableItems(List<SchemeAssessment> assessments) {
        List<AssessmentTableDTO> items = new ArrayList<>();
        if (assessments == null || assessments.isEmpty()) {
            return items;
        }
        for (SchemeAssessment assessment : assessments) {
            if (assessment == null) {
                continue;
            }
            AssessmentTableDTO dto = new AssessmentTableDTO();
            dto.setGrade(assessment.getGrade());
            dto.setAssessmentName(assessment.getName());
            dto.setDueDate(assessment.getDueDate());
            dto.setStartTime(assessment.getStartTime());
            dto.setEndTime(assessment.getEndTime());
            dto.setLocation(assessment.getLocation());
            dto.setGrade(assessment.getGrade());
            BigDecimal weight = assessment.getWeight();
            dto.setWeights(weight == null ? null : new BigDecimal[]{weight});
            items.add(dto);
        }
        return items;
    }
}
