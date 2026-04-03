package org.tracker.gpatracker.syllabus.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.tracker.gpatracker.syllabus.dto.SyllabusExtractionJobResponse;
import org.tracker.gpatracker.syllabus.model.SyllabusExtractionJob;
import org.tracker.gpatracker.syllabus.service.GeminiSyllabusExtractionService;

@RestController
@RequestMapping("/api/syllabus/upload")
public class SyllabusUploadController {

    private static final Logger logger = LoggerFactory.getLogger(SyllabusUploadController.class);
    private static final long MAX_UPLOAD_BYTES = 10L * 1024L * 1024L; // 10 MB

    private final GeminiSyllabusExtractionService extractionService;

    public SyllabusUploadController(GeminiSyllabusExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @PostMapping("/submit")
    public ResponseEntity<SyllabusExtractionJobResponse> uploadSyllabus(@RequestParam("file") MultipartFile file,
                                                               @RequestParam String courseCode,
                                                               @RequestParam String term) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ResponseEntity.status(413).build();
        }

        logger.info("POST /api/syllabus/upload/{}/{} — file size: {} bytes", courseCode, term, file.getSize());
        SyllabusExtractionJob job = extractionService.createJob(courseCode, term);
        byte[] pdfBytes;
        try {
            pdfBytes = file.getBytes();
        } catch (java.io.IOException ex) {
            logger.error("POST /api/syllabus/upload — failed to read file for course: {}", courseCode, ex);
            return ResponseEntity.internalServerError().build();
        }
        extractionService.processJobAsync(job.getId(), pdfBytes, courseCode, term);
        logger.info("POST /api/syllabus/upload — job created: {} for course: {}", job.getId(), courseCode);
        return ResponseEntity.accepted().body(SyllabusExtractionJobResponse.from(job));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<SyllabusExtractionJobResponse> getJobStatus(@PathVariable String jobId) {
        SyllabusExtractionJob job = extractionService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(SyllabusExtractionJobResponse.from(job));
    }

    /**@GetMapping("/{courseCode}/{term}/assessments")
    public ResponseEntity<Map<String, List<AssessmentTableDTO>>> getAssessments(@PathVariable String courseCode,
                                                                                @PathVariable String term) {
        AbstractSyllabusDocument doc = syllabusAssessmentService.getSyllabusDocument(courseCode, term);
        return ResponseEntity.ok(syllabusAssessmentService.getNormalizedAssessmentItems(doc));
    }**/
}
