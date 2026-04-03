package org.tracker.gpatracker.courses.service;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.courses.model.DueDateOverride;
import org.tracker.gpatracker.courses.repository.DueDateOverrideRepository;
import java.time.LocalDate;
import java.util.List;

@Service
public class DueDateOverrideService {

    private final DueDateOverrideRepository dueDateOverrideRepository;
    private final DueDateConsensusService dueDateConsensusService;

    public DueDateOverrideService(DueDateOverrideRepository dueDateOverrideRepository,
                                   DueDateConsensusService dueDateConsensusService) {
        this.dueDateOverrideRepository = dueDateOverrideRepository;
        this.dueDateConsensusService = dueDateConsensusService;
    }

    public void deleteDueDateOverride(String courseCode, String assessmentName, Student student) {
        dueDateOverrideRepository
                .deleteByStudentIdAndAssessmentNameAndCourseCode(student.getId(), assessmentName, courseCode);
        dueDateConsensusService.setConsensusDueDate();
    }

    public void createOrUpdateDueDateOverride(String courseCode, String assessmentName,LocalDate newDueDate, Student student) {
        DueDateOverride dueDateOverride = new DueDateOverride();
        if (dueDateOverrideRepository.existsByStudentIdAndAssessmentNameAndCourseCode(student.getId(), assessmentName, courseCode)) {
           return;
        }
        dueDateOverride.setCourseCode(courseCode);
        dueDateOverride.setAssessmentName(assessmentName);
        dueDateOverride.setProposedDueDate(newDueDate);
        dueDateOverride.setStudent(student);
        dueDateOverrideRepository.save(dueDateOverride);
        dueDateConsensusService.setConsensusDueDate();
    }

    public List<DueDateOverride> getDueDateOverride(Long studentId) {
        return dueDateOverrideRepository
                .findByStudentId(studentId);
    }

}
