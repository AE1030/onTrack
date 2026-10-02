package org.tracker.gpatracker.accounts.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.accounts.dto.DashboardDTO;
import org.tracker.gpatracker.accounts.dto.SetTargetGpaDTO;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.accounts.service.gpautils.GpaScale;
import org.tracker.gpatracker.leaderboard.service.LeaderboardService;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class DashboardService {
    private final StudentService studentService;
    private final StudentRepo studentRepo;
    private final LeaderboardService leaderboardService;

    public DashboardService(StudentService studentService, StudentRepo studentRepo,
                            LeaderboardService leaderboardService) {
        this.studentService = studentService;
        this.studentRepo = studentRepo;
        this.leaderboardService = leaderboardService;
    }

    public DashboardDTO getDashboardData() {
        Student student = studentService.getStudentAccount();

        DashboardDTO dto = new DashboardDTO();
        dto.setUsername(student.getUser().getUsername());
        dto.setGpa4(student.getGpa4());
        dto.setGpa12(student.getGpa12());

        // On the leaderboard, the target shown is the one the board scores against, not the
        // personal one, so the two can never disagree.
        Optional<BigDecimal> locked = leaderboardService.lockedTargetGpa12(student.getId());
        if (locked.isPresent()) {
            dto.setTargetGpa12(locked.get());
            dto.setTargetGpa4(GpaScale.toFourPoint(locked.get()));
            dto.setTargetLocked(true);
        } else {
            dto.setTargetGpa4(student.getTargetGpa4());
            dto.setTargetGpa12(student.getTargetGpa12());
        }
        return dto;
    }

    public void setTargetGpa(SetTargetGpaDTO dto) {
        Student student = studentService.getStudentAccount();
        if (leaderboardService.lockedTargetGpa12(student.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Your target is locked while you are on the leaderboard.");
        }
        if (dto.getTargetGpa4() != null) {
            student.setTargetGpa4(dto.getTargetGpa4());
        }
        if (dto.getTargetGpa12() != null) {
            student.setTargetGpa12(dto.getTargetGpa12());
        }
        studentRepo.save(student);
    }
}
