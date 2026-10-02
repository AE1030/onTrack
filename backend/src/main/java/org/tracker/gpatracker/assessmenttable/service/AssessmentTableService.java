package org.tracker.gpatracker.assessmenttable.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.tracker.gpatracker.terms.TermService;
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
import java.time.Instant;
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
    private final TermService termService;

    @Value("${app.current-term}")
    private String currentTerm;

    public AssessmentTableService(AssessmentTableDocumentRepository repository,
                                   StudentService studentService,
                                   SyllabusAssessmentService syllabusAssessmentService,
                                   ApplicationEventPublisher eventPublisher,
                                   TermService termService) {
        this.repository = repository;
        this.studentService = studentService;
        this.syllabusAssessmentService = syllabusAssessmentService;
        this.eventPublisher = eventPublisher;
        this.termService = termService;
    }

    public AssessmentTableDocument saveToRepo(SaveAssessmentTableDTO dto) {
        Long studentId = studentService.getStudentID();
        // Every write lands on the current term. The DTO's term was already checked by the
        // caller; this uses the configured value so the two can never disagree.
        String term = currentTerm;

        AssessmentTableDocument document = new AssessmentTableDocument();
        document.setCourseCode(dto.getCourseCode());
        document.setTerm(term);
        document.setSchemes(dto.getSchemes());
        document.setOwnerId(studentId);

        // The write target is chosen only from a row this student already owns. Honouring
        // dto.getId() let a caller name any id, and a save with an id set is a merge -- so another
        // student's assessment table could be overwritten. Leaving the id null makes this an insert
        // instead. The owner filter cannot catch this on its own, because an id supplied by the
        // caller would be looked up before ownership is ever considered.
        AssessmentTableDocument existing = repository
                .findByOwnerIdAndCourseCodeAndTerm(studentId, dto.getCourseCode(), term)
                .orElse(null);
        if (existing != null) {
            document.setId(existing.getId());
        }

        // Same read, second purpose: the stored document is the only record of when each grade
        // first appeared, and the incoming DTO carries no trustworthy version of that.
        GradeStamper.stamp(document.getSchemes(),
                existing == null ? null : existing.getSchemes(),
                Instant.now());

        AssessmentTableDocument saved = insertOrMerge(document, studentId, dto.getCourseCode(), term);
        eventPublisher.publishEvent(new AssessmentTableUpdatedEvent(this, studentId));
        return saved;
    }

    /**
     * Saves the document, recovering from the one race the read above cannot close.
     *
     * <p>The read-then-write sequence has a gap: two saves for a course with no table yet can both
     * find nothing and both insert. The unique constraint on (student_id, course_code, term) is what
     * stops that becoming two rows. The recovery is simply what this request would have done had it
     * read a moment later: re-read, and merge onto the row that won.
     *
     * <p>Catches {@link DataIntegrityViolationException} rather than its {@link DuplicateKeyException}
     * subclass. On Mongo the driver reported a duplicate key precisely; Hibernate reports a constraint
     * violation, and Spring's {@code HibernateJpaDialect} translates that to the broader
     * {@code DataIntegrityViolationException} without narrowing it to the duplicate-key subtype.
     * Catching only the subclass would compile, pass a unit test with a mocked repository, and then
     * let the real race through in production.
     *
     * <p>The wider net is safe because the recovery is self-checking: any other integrity failure
     * (a missing student row, say) finds nothing on the re-read and rethrows the original.
     *
     * <p>Once, not in a loop. A second violation means something other than a race is wrong, and
     * retrying forever would hide it.
     */
    private AssessmentTableDocument insertOrMerge(AssessmentTableDocument document,
                                                  Long studentId,
                                                  String courseCode,
                                                  String term) {
        try {
            return repository.save(document);
        } catch (DataIntegrityViolationException e) {
            logger.warn("Concurrent insert for student {} course {} term {}; merging onto the winner",
                    studentId, courseCode, term);
            AssessmentTableDocument winner = repository
                    .findByOwnerIdAndCourseCodeAndTerm(studentId, courseCode, term)
                    .orElseThrow(() -> e);
            document.setId(winner.getId());
            // Now an update rather than an insert, so the unique constraint has nothing to object to.
            return repository.save(document);
        }
    }

    /**
     * Saves the table and the grade it works out to.
     *
     * <p>The table write goes first and is undone by hand if the grade write refuses, which keeps
     * the two from drifting apart the way two independent client calls could.
     *
     * <p>This compensation was originally forced: the table lived in Mongo and the grade in
     * Postgres, so no single transaction could span them. Both are in Postgres now, so a plain
     * {@code @Transactional} could replace all of it. That change is deliberately <em>not</em> made
     * here, because the table write publishes {@code AssessmentTableUpdatedEvent} and the listener
     * chain reaches {@code GoogleCalendarSyncService}, which makes HTTP calls to Google. Wrapping
     * this method in a transaction would hold one open across those calls. Collapsing the
     * compensation is worth doing, but it needs the listeners moved to
     * {@code @TransactionalEventListener(AFTER_COMMIT)} first, and that is its own change.
     *
     * <p>The reverse order is not possible to compensate as cleanly: the grade write triggers a GPA
     * recalculation, and unpicking that after the fact is worse than re-saving one row.
     */
    public AssessmentTableDocument saveTableAndGrade(SaveAssessmentTableDTO dto) {
        // Before anything is read or written, in either store. A past term stops here, so the
        // compensation path below can never be reached by a request that was never allowed.
        termService.requireEditable(dto.getTerm());

        Long studentId = studentService.getStudentID();

        // Read the current document before overwriting it — this is the only copy of the
        // pre-save state, and compensation has nothing to restore from without it.
        AssessmentTableDocument previous = repository
                .findByOwnerIdAndCourseCodeAndTerm(studentId, dto.getCourseCode(), currentTerm)
                .orElse(null);
        String previousId = previous == null ? null : previous.getId();
        List<AssessmentScheme> previousSchemes = previous == null ? null : previous.getSchemes();

        AssessmentTableDocument saved = saveToRepo(dto);

        if (dto.getGrade() == null) {
            return saved;
        }

        try {
            studentService.updateCourseGrade(dto.getCourseCode(), dto.getTerm(), dto.getGrade());
        } catch (RuntimeException ex) {
            compensateTableWrite(studentId, dto.getCourseCode(), previousId, previousSchemes, saved);
            throw ex;
        }

        return saved;
    }

    private void compensateTableWrite(Long studentId,
                                      String courseCode,
                                      String previousId,
                                      List<AssessmentScheme> previousSchemes,
                                      AssessmentTableDocument saved) {
        try {
            if (previousId == null) {
                // Nothing was stored before, so the save was an insert. Remove it.
                repository.deleteById(saved.getId());
            } else {
                AssessmentTableDocument restored = new AssessmentTableDocument();
                restored.setId(previousId);
                restored.setCourseCode(courseCode);
                restored.setTerm(currentTerm);
                restored.setSchemes(previousSchemes);
                restored.setOwnerId(studentId);
                repository.save(restored);
            }
            eventPublisher.publishEvent(new AssessmentTableUpdatedEvent(this, studentId));
        } catch (RuntimeException compensationFailure) {
            // Swallowed so the original failure is what reaches the client, but this
            // leaves a stored table the grade never matched — log it loudly.
            logger.error("Failed to roll back assessment table for student {} course {} " +
                            "after the grade write failed; table and grade are now out of sync",
                    studentId, courseCode, compensationFailure);
        }
    }

    public void deleteByCourseCodeAndTerm(Long studentId, String courseCode, String term) {
        repository.deleteByOwnerIdAndCourseCodeAndTerm(studentId, courseCode, term);
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

        if (repository.findByOwnerIdAndCourseCodeAndTerm(studentId, courseCode, currentTerm).isPresent()) {
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
        // Server-built, so it is the current term by construction. Set anyway, so the DTO is
        // never half-populated and a future caller cannot read a null term off it.
        dto.setTerm(currentTerm);

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
                .findByOwnerIdAndCourseCodeAndTerm(studentId, courseCode, term)
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
