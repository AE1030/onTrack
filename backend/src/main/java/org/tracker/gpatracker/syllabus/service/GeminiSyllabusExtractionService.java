package org.tracker.gpatracker.syllabus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.assessmenttable.service.AssessmentTableService;
import org.tracker.gpatracker.syllabus.config.GeminiPromptConfig;
import org.tracker.gpatracker.syllabus.model.Assessments;
import org.tracker.gpatracker.syllabus.model.StudentCourseTermId;
import org.tracker.gpatracker.syllabus.model.ExtractionMetadata;
import org.tracker.gpatracker.syllabus.model.GradingScheme;
import org.tracker.gpatracker.syllabus.model.JobStatus;
import org.tracker.gpatracker.syllabus.model.SyllabusExtractionJob;
import org.tracker.gpatracker.syllabus.model.UserSyllabusDocument;
import org.tracker.gpatracker.syllabus.repository.SyllabusExtractionJobRepository;
import org.tracker.gpatracker.syllabus.repository.UserSyllabusRepository;
import org.tracker.gpatracker.syllabus.exception.GeminiApiException;
import org.tracker.gpatracker.syllabus.exception.GeminiParseException;
import org.tracker.gpatracker.syllabus.exception.InvalidSyllabusException;
import org.tracker.gpatracker.syllabus.exception.SyllabusErrorType;

@Service
public class GeminiSyllabusExtractionService {
    private static final Logger logger = LoggerFactory.getLogger(GeminiSyllabusExtractionService.class);
    private static final String MODEL = "gemini-2.5-pro";
    private static final double TEMPERATURE = 0.0;
    private static final int THINKING_BUDGET = 8311;
    private static final String NO_SYLLABUS_ERROR_CODE = "NO_SYLLABUS_DATA";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final StudentService studentService;
    private final UserSyllabusRepository userSyllabusRepository;
    private final SyllabusExtractionJobRepository jobRepository;
    private final SyllabusUploadQuotaService quotaService;
    private final AssessmentTableService assessmentTableService;
    private final Client geminiClient;

    public GeminiSyllabusExtractionService(@Value("${gemini.api.key}") String apiKey,
                                           StudentService studentService,
                                           UserSyllabusRepository userSyllabusRepository,
                                           SyllabusExtractionJobRepository jobRepository,
                                           SyllabusUploadQuotaService quotaService,
                                           AssessmentTableService assessmentTableService) {
        this.geminiClient = Client.builder()
                .apiKey(apiKey)
                .build();
        this.studentService = studentService;
        this.userSyllabusRepository = userSyllabusRepository;
        this.jobRepository = jobRepository;
        this.quotaService = quotaService;
        this.assessmentTableService = assessmentTableService;
    }


    public SyllabusExtractionJob createJob(String courseCode, String term) {
        Long studentId = studentService.getStudentID();
        int remaining = quotaService.consumeUploadQuota(studentId);
        SyllabusExtractionJob job = new SyllabusExtractionJob();
        job.setOwnerId(studentId);
        job.setCourseCode(courseCode);
        job.setTerm(term);
        job.setStatus(JobStatus.QUEUED);
        job.setRemainingUploads(remaining);
        // createdAt/updatedAt are filled by JPA auditing on save -- see BaseEntity.
        return jobRepository.save(job);
    }

    public SyllabusExtractionJob getJob(String jobId) {
        return jobRepository.findById(jobId).orElse(null);
    }

    @Async
    public void processJobAsync(String jobId, byte[] pdfBytes, String courseCode, String term) {
        SyllabusExtractionJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        try {
            job.setStatus(JobStatus.PROCESSING);
            jobRepository.save(job);
            extractAndSaveInternal(pdfBytes, courseCode, term, job.getOwnerId());
            try {
                assessmentTableService.refreshFromSyllabus(courseCode);
            } catch (Exception e) {
                logger.warn("Failed to refresh assessment table for {}: {}", courseCode, e.getMessage());
            }
            job.setStatus(JobStatus.DONE);
            job.setErrorType(null);
            job.setRemainingUploads(quotaService.getRemainingUploads(job.getOwnerId()));
            jobRepository.save(job);
        } catch (Exception ex) {
            job.setStatus(JobStatus.FAILED);
            String message = ex.getMessage();
            job.setError(message == null ? "Gemini extraction failed." : message);
            job.setErrorType(classifyError(ex));
            // Restore quota for non-chargeable failures (API errors, parse errors)
            // Keep the charge for invalid syllabus uploads
            if (job.getErrorType() != SyllabusErrorType.INVALID_SYLLABUS) {
                job.setRemainingUploads(quotaService.restoreQuota(job.getOwnerId()));
            } else {
                job.setRemainingUploads(quotaService.getRemainingUploads(job.getOwnerId()));
            }
            jobRepository.save(job);
        }
    }

    private UserSyllabusDocument extractAndSaveInternal(byte[] pdfBytes, String courseCode, String term, Long studentId) {
        String prompt = GeminiPromptConfig.USER_PROMPT;

        Content content = Content.fromParts(
                Part.fromBytes(pdfBytes, "application/pdf"),
                Part.fromText(prompt)
        );

        GenerateContentConfig config = GenerateContentConfig.builder()
                .temperature((float) TEMPERATURE)
                .responseMimeType("application/json")
                .thinkingConfig(ThinkingConfig.builder().thinkingBudget(THINKING_BUDGET).build())
                .systemInstruction(Content.fromParts(Part.fromText(GeminiPromptConfig.SYSTEM_PROMPT)))
                .build();

        GenerateContentResponse response;
        try {
            response = geminiClient.models.generateContent(
                    MODEL,
                    content,
                    config
            );
        } catch (Exception ex) {
            throw new GeminiApiException("Gemini API call failed.", ex);
        }

        String responseText = response.text();
        logger.info("Gemini raw response: {}", responseText);
        logger.info("extractAndSaveInternal received response. empty={}", responseText == null || responseText.isBlank());
        if (responseText == null || responseText.isBlank()) {
            throw new IllegalStateException("Empty response from Gemini.");
        }
        logger.info("extractAndSaveInternal response content. responseText={}", responseText);


        Assessments assessments = parseAssessments(responseText);
        UserSyllabusDocument document = new UserSyllabusDocument();
        document.setStudentId(studentId);
        document.setId(new StudentCourseTermId(studentId, courseCode, term));
        document.setCourseCode(courseCode);
        document.setTerm(term);
        document.setAssessments(assessments);
        document.setExtraction(new ExtractionMetadata(MODEL, TEMPERATURE));

        return userSyllabusRepository.save(document);
    }

    private Assessments parseAssessments(String responseText) {
        try {
            logger.info("parseAssessments start. responseLength={}", responseText == null ? 0 : responseText.length());
            JsonNode root = objectMapper.readTree(responseText);
            logger.info("parseAssessments parsed root. hasGradingScheme={}, hasSelectionRule={}, hasSchemes={}, hasError={}",
                    root.has("grading_scheme"),
                    root.has("selection_rule"),
                    root.has("schemes"),
                    root.has("error"));
            JsonNode errorNode = root.get("error");
            if (errorNode != null && NO_SYLLABUS_ERROR_CODE.equals(errorNode.asText())) {
                String reason = root.has("reason") ? root.get("reason").asText() : "unknown";
                throw new InvalidSyllabusException(NO_SYLLABUS_ERROR_CODE + ": " + reason);
            }
            JsonNode gradingSchemeNode = root.get("grading_scheme");
            if (gradingSchemeNode == null && root.has("selection_rule") && root.has("schemes")) {
                gradingSchemeNode = root;
            }
            if (gradingSchemeNode == null || gradingSchemeNode.isNull()) {
                throw new GeminiParseException("Missing grading_scheme in Gemini response.", null);
            }
            GradingScheme gradingScheme = objectMapper.treeToValue(gradingSchemeNode, GradingScheme.class);
            logger.info("parseAssessments mapped gradingScheme. schemesCount={}",
                    gradingScheme.getSchemes() == null ? 0 : gradingScheme.getSchemes().size());
            Assessments assessments = new Assessments();
            assessments.setGradingScheme(gradingScheme);
            logger.info("parseAssessments completed.");
            return assessments;
        } catch (JsonProcessingException ex) {
            throw new GeminiParseException("Failed to parse Gemini response JSON.", ex);
        }
    }

    private SyllabusErrorType classifyError(Exception ex) {
        if (ex instanceof InvalidSyllabusException) {
            return SyllabusErrorType.INVALID_SYLLABUS;
        }
        if (ex instanceof GeminiParseException) {
            return SyllabusErrorType.PARSE_ERROR;
        }
        if (ex instanceof GeminiApiException) {
            return SyllabusErrorType.GEMINI_ERROR;
        }
        return SyllabusErrorType.BACKEND_ERROR;
    }

}
