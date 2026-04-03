package org.tracker.gpatracker.accounts.controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.accounts.dto.GPACalculatorResponse;
import java.io.IOException;
import org.tracker.gpatracker.accounts.service.TranscriptUploadService;

@RestController
@RequestMapping("/api/transcript")
public class TranscriptUploadController {

    private static final Logger logger = LoggerFactory.getLogger(TranscriptUploadController.class);
    private final TranscriptUploadService service;

    public TranscriptUploadController(TranscriptUploadService service) {
        this.service = service;
    }

    // 100 MB max for example **double check this number here****
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;

    @PostMapping(value = "/calculateGPA",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GPACalculatorResponse> uploadTranscriptForGPACalc(@RequestParam("file") MultipartFile file) throws IOException {
        logger.info("POST /api/transcript/calculateGPA — file size: {} bytes", file.getSize());
        validatePdfUpload(file);
        GPACalculatorResponse result = service.processTranscriptForGPACalc(file);
        logger.info("POST /api/transcript/calculateGPA — success, GPA: {}", result.getGpa());
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/add-current-courses",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> uploadTranscriptForGetCurrentCourses(@RequestParam("file") MultipartFile file) {
        validatePdfUpload(file);
        try {
            service.processTranscriptForGetCurrentCourses(file);
        } catch (Exception e) {
            logger.error("POST /api/transcript/add-current-courses — failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to process transcript.");
        }
        logger.info("POST /api/transcript/add-current-courses — success");
        return ResponseEntity.ok("Extraction Complete. Current courses have been added.");
    }

    private void validatePdfUpload(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty.");
        }
        if (!("application/pdf").equalsIgnoreCase(file.getContentType())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF files are allowed.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File is too large.");
        }
    }
}
