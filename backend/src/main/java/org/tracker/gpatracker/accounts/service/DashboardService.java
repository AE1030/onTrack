package org.tracker.gpatracker.accounts.service;

import org.springframework.stereotype.Service;
import org.tracker.gpatracker.accounts.dto.DashboardDTO;
import org.tracker.gpatracker.accounts.dto.SetTargetGpaDTO;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
@Service
public class DashboardService {
    private final StudentService studentService;
    private final StudentRepo studentRepo;

    public DashboardService(StudentService studentService, StudentRepo studentRepo) {
        this.studentService = studentService;
        this.studentRepo = studentRepo;
    }

    public DashboardDTO getDashboardData() {
        Student student = studentService.getStudentAccount();

        DashboardDTO dto = new DashboardDTO();
        dto.setUsername(student.getUser().getUsername());
        dto.setGpa4(student.getGpa4());
        dto.setGpa12(student.getGpa12());
        dto.setTargetGpa4(student.getTargetGpa4());
        dto.setTargetGpa12(student.getTargetGpa12());
        return dto;
    }

    public void setTargetGpa(SetTargetGpaDTO dto) {
        Student student = studentService.getStudentAccount();
        if (dto.getTargetGpa4() != null) {
            student.setTargetGpa4(dto.getTargetGpa4());
        }
        if (dto.getTargetGpa12() != null) {
            student.setTargetGpa12(dto.getTargetGpa12());
        }
        studentRepo.save(student);
    }
}
