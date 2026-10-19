package org.tracker.gpatracker.accounts.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.tracker.gpatracker.assessmenttable.service.AssessmentTableService;
import org.tracker.gpatracker.courses.model.Course;
import org.tracker.gpatracker.courses.repository.CourseRepository;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Multi-term courses through the transcript parser. The PDF step is stubbed out, so each test hands
 * the parser the lines PDFBox would have produced.
 */
@ExtendWith(MockitoExtension.class)
class TranscriptUploadServiceTest {

    @Mock
    private CourseRepository repo;
    @Mock
    private StudentService studentService;
    @Mock
    private AssessmentTableService assessmentTableService;

    private TranscriptUploadService service;

    private final MockMultipartFile file =
            new MockMultipartFile("file", "transcript.pdf", "application/pdf", new byte[]{1});

    @BeforeEach
    void setUp() {
        service = spy(new TranscriptUploadService(repo, studentService, assessmentTableService, 2026, "Winter"));
    }

    private void transcript(String... lines) throws IOException {
        doReturn(lines).when(service).convertToString(any());
    }

    private static Course course(String code, long credits) {
        return new Course(1L, code, "Some Course", credits);
    }

    @Test
    @DisplayName("an A/B S course printed with its S counts once, with the second half's units")
    void abSCountsTowardTheGpa() throws IOException {
        transcript(
                "--- 2025 Fall ---",
                "BIOLOGY 4F06AS Senior Project 0.00/0.00 MT",
                "--- 2026 Winter ---",
                "BIOLOGY 4F06BS Senior Project 6.00/6.00 A",
                "COMPSCI 2C03 Data Structures 3.00/3.00 B");

        // (6 x 11 + 3 x 8) / 9 = 10.00. Before the S was accepted, the thesis line was not a course
        // at all and this came out as 8.00.
        assertThat(service.parseGpa12(file)).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("an A/B S course printed without its S still enrols, under its A/B S catalog code")
    void abSWithoutTheSFallsBackToTheCatalog() throws IOException {
        Course thesis = course("BIOLOGY 4F06 A/B S", 6);
        when(repo.findBycourseCode("BIOLOGY 4F06 A/B")).thenReturn(Optional.empty());
        when(repo.findBycourseCode("BIOLOGY 4F06 A/B S")).thenReturn(Optional.of(thesis));
        transcript(
                "--- 2026 Winter ---",
                "BIOLOGY 4F06B Senior Project 6.00/0.00");

        service.processTranscriptForGetCurrentCourses(file);

        verify(studentService).addCurrentCourses(thesis, null);
        verify(assessmentTableService).createFromSyllabus("BIOLOGY 4F06 A/B S");
    }

    @Test
    @DisplayName("a code that exists as both A/B and A/B S resolves to the exact A/B match")
    void exactMatchWins() throws IOException {
        Course plain = course("INNOVATE 4EX6 A/B", 6);
        when(repo.findBycourseCode("INNOVATE 4EX6 A/B")).thenReturn(Optional.of(plain));
        transcript(
                "--- 2026 Winter ---",
                "INNOVATE 4EX6A Experiential Project 0.00/0.00");

        service.processTranscriptForGetCurrentCourses(file);

        verify(studentService).addCurrentCourses(plain, null);
        verify(repo, never()).findBycourseCode("INNOVATE 4EX6 A/B S");
    }

    @Test
    @DisplayName("an A/B S course printed with its S enrols directly")
    void abSWithTheSEnrolsDirectly() throws IOException {
        Course thesis = course("BIOLOGY 4F06 A/B S", 6);
        when(repo.findBycourseCode("BIOLOGY 4F06 A/B S")).thenReturn(Optional.of(thesis));
        transcript(
                "--- 2026 Winter ---",
                "BIOLOGY 4F06AS Senior Project 0.00/0.00");

        service.processTranscriptForGetCurrentCourses(file);

        verify(studentService).addCurrentCourses(thesis, null);
    }
}
