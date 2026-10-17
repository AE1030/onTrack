package org.tracker.gpatracker.syllabus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tracker.gpatracker.syllabus.exception.SyllabusErrorType;
import org.tracker.gpatracker.syllabus.model.JobStatus;
import org.tracker.gpatracker.syllabus.model.SyllabusExtractionJob;
import org.tracker.gpatracker.syllabus.repository.SyllabusExtractionJobRepository;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaleSyllabusJobSweeperTest {

    private static final Long STUDENT = 7L;

    private SyllabusExtractionJobRepository jobRepository;
    private SyllabusUploadQuotaService quotaService;
    private StaleSyllabusJobSweeper sweeper;

    @BeforeEach
    void setUp() {
        jobRepository = mock(SyllabusExtractionJobRepository.class);
        quotaService = mock(SyllabusUploadQuotaService.class);
        sweeper = new StaleSyllabusJobSweeper(jobRepository, quotaService);
    }

    private SyllabusExtractionJob staleJob(String id) {
        SyllabusExtractionJob job = new SyllabusExtractionJob();
        job.setId(id);
        job.setOwnerId(STUDENT);
        job.setStatus(JobStatus.PROCESSING);
        when(jobRepository.findByOwnerIdAndStatusInAndCreatedAtBefore(eq(STUDENT), any(), any()))
                .thenReturn(List.of(job));
        return job;
    }

    @Test
    @DisplayName("a stuck job is failed as TIMED_OUT and its upload is refunded")
    void stuckJobRefunded() {
        staleJob("job-1");
        when(jobRepository.failIfActive(eq("job-1"), eq(STUDENT), eq(SyllabusErrorType.TIMED_OUT), anyString(), any()))
                .thenReturn(1);
        when(quotaService.restoreQuota(STUDENT)).thenReturn(2);

        sweeper.sweep(STUDENT);

        verify(quotaService).restoreQuota(STUDENT);
        verify(jobRepository).setRemainingUploads("job-1", STUDENT, 2);
    }

    @Test
    @DisplayName("a job that already failed on its own between the read and the update is not refunded again")
    void lostRaceNotRefunded() {
        staleJob("job-1");
        when(jobRepository.failIfActive(anyString(), any(), any(), anyString(), any())).thenReturn(0);

        sweeper.sweep(STUDENT);

        verify(quotaService, never()).restoreQuota(any());
        verify(jobRepository, never()).setRemainingUploads(anyString(), any(), anyInt());
    }
}
