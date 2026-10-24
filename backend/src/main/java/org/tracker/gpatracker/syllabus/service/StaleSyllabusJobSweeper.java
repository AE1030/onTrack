package org.tracker.gpatracker.syllabus.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.syllabus.exception.SyllabusErrorType;
import org.tracker.gpatracker.syllabus.model.JobStatus;
import org.tracker.gpatracker.syllabus.model.SyllabusExtractionJob;
import org.tracker.gpatracker.syllabus.repository.SyllabusExtractionJobRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;

/**
 * Fails and refunds syllabus jobs that will never finish.
 *
 * <p>An extraction runs in memory on whichever instance took the upload. If that instance restarts
 * mid-job, the row is left QUEUED or PROCESSING with nothing left to move it on, and the upload it
 * charged is never given back.
 *
 * <p>There is no scheduled sweep: Cloud Run has no minimum instances, so a cron cannot be relied on
 * to fire (see JobTriggerController). Instead this runs, for one student, at the start of every
 * request that shows or spends their uploads: the remaining-uploads count, a new upload, a status
 * poll, and the recent-jobs lookup. A stuck row can sit in the table until the student comes back,
 * but they can never see a count that is missing the refund.
 */
@Service
public class StaleSyllabusJobSweeper {

    private static final Logger logger = LoggerFactory.getLogger(StaleSyllabusJobSweeper.class);

    /** Far beyond a normal extraction (a minute or two). The client gives up polling at about the same point. */
    static final Duration STALE_AFTER = Duration.ofMinutes(15);
    static final String TIMED_OUT_MESSAGE =
            "This upload took too long and was cancelled. It did not count against your uploads, so you can try again.";

    private final SyllabusExtractionJobRepository jobRepository;
    private final SyllabusUploadQuotaService quotaService;

    public StaleSyllabusJobSweeper(SyllabusExtractionJobRepository jobRepository,
                                   SyllabusUploadQuotaService quotaService) {
        this.jobRepository = jobRepository;
        this.quotaService = quotaService;
    }

    public void sweep(Long studentId) {
        Instant now = Instant.now();
        var stale = jobRepository.findByOwnerIdAndStatusInAndCreatedAtBefore(
                studentId, EnumSet.of(JobStatus.QUEUED, JobStatus.PROCESSING), now.minus(STALE_AFTER));
        for (SyllabusExtractionJob job : stale) {
            // Lost the race to the job's own failure handling, which has already refunded it.
            if (jobRepository.failIfActive(job.getId(), studentId, SyllabusErrorType.TIMED_OUT, TIMED_OUT_MESSAGE, now) == 0) {
                continue;
            }
            int remaining = quotaService.restoreQuota(studentId);
            jobRepository.setRemainingUploads(job.getId(), studentId, remaining);
            logger.warn("Syllabus job {} timed out in {}; upload refunded", job.getId(), job.getStatus());
        }
    }
}
