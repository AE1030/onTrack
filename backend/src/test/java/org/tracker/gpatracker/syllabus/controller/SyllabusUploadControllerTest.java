package org.tracker.gpatracker.syllabus.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.tracker.gpatracker.syllabus.dto.SyllabusExtractionJobResponse;
import org.tracker.gpatracker.syllabus.model.SyllabusExtractionJob;
import org.tracker.gpatracker.syllabus.service.GeminiSyllabusExtractionService;
import org.tracker.gpatracker.terms.TermService;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyllabusUploadControllerTest {

    private GeminiSyllabusExtractionService extractionService;
    private SyllabusUploadController controller;

    @BeforeEach
    void setUp() {
        extractionService = mock(GeminiSyllabusExtractionService.class);
        controller = new SyllabusUploadController(extractionService, mock(TermService.class));
    }

    @Test
    @DisplayName("a file that is not a PDF is refused before an upload is charged or Gemini is called")
    void nonPdfRejectedWithoutCharge() {
        // Claims to be a PDF, but the bytes say otherwise.
        MockMultipartFile file = new MockMultipartFile(
                "file", "syllabus.pdf", "application/pdf", "hello, not a pdf".getBytes(StandardCharsets.US_ASCII));

        ResponseEntity<SyllabusExtractionJobResponse> response = controller.uploadSyllabus(file, "COMP 2140", "Fall 2026");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        verify(extractionService, never()).createJob(anyString(), anyString());
        verify(extractionService, never()).processJobAsync(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a real PDF is charged and queued")
    void pdfAccepted() {
        SyllabusExtractionJob job = new SyllabusExtractionJob();
        when(extractionService.createJob("COMP 2140", "Fall 2026")).thenReturn(job);
        MockMultipartFile file = new MockMultipartFile(
                "file", "syllabus.pdf", "application/pdf", "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII));

        ResponseEntity<SyllabusExtractionJobResponse> response = controller.uploadSyllabus(file, "COMP 2140", "Fall 2026");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(extractionService).createJob("COMP 2140", "Fall 2026");
    }

    @Test
    @DisplayName("isPdf checks the leading bytes only")
    void isPdf() {
        assertThat(SyllabusUploadController.isPdf("%PDF-1.4".getBytes(StandardCharsets.US_ASCII))).isTrue();
        assertThat(SyllabusUploadController.isPdf("%PDF".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(SyllabusUploadController.isPdf(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D})).isFalse();
        assertThat(SyllabusUploadController.isPdf(new byte[0])).isFalse();
    }
}
