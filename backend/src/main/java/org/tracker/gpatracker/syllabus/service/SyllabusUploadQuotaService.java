package org.tracker.gpatracker.syllabus.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.syllabus.model.SyllabusUploadQuota;
import org.tracker.gpatracker.syllabus.repository.SyllabusUploadQuotaRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SyllabusUploadQuotaService {
    private static final int DEFAULT_REMAINING_UPLOADS = 7;
    private static final Duration MIN_UPLOAD_INTERVAL = Duration.ofSeconds(20);

    private final ConcurrentHashMap<Long, Object> studentLocks = new ConcurrentHashMap<>();

    private final SyllabusUploadQuotaRepository quotaRepository;
    private final StudentService studentService;

    public SyllabusUploadQuotaService(SyllabusUploadQuotaRepository quotaRepository,
                                      StudentService studentService) {
        this.quotaRepository = quotaRepository;
        this.studentService = studentService;
    }

    /**
     * Atomically checks quota and consumes one upload in a single operation.
     * Returns the remaining uploads after consumption.
     */
    public int consumeUploadQuota(Long studentId) {
        Object lock = studentLocks.computeIfAbsent(studentId, k -> new Object());
        synchronized (lock) {
            Instant now = Instant.now();
            SyllabusUploadQuota quota = quotaRepository.findById(studentId)
                    .orElseGet(() -> createDefaultQuota(studentId, now));

            if (quota.getRemainingUploads() <= 0) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Upload quota exhausted.");
            }

            Instant lastUploadAt = quota.getLastUploadAt();
            if (lastUploadAt != null) {
                Duration sinceLast = Duration.between(lastUploadAt, now);
                if (sinceLast.compareTo(MIN_UPLOAD_INTERVAL) < 0) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Upload rate limit exceeded.");
                }
            }

            quota.setRemainingUploads(quota.getRemainingUploads() - 1);
            quota.setLastUploadAt(now);
            quota.setUpdatedAt(now);
            quotaRepository.save(quota);
            return quota.getRemainingUploads();
        }
    }

    /**
     * Restores one upload quota unit (e.g. after a non-chargeable failure).
     */
    public int restoreQuota(Long studentId) {
        Object lock = studentLocks.computeIfAbsent(studentId, k -> new Object());
        synchronized (lock) {
            Instant now = Instant.now();
            SyllabusUploadQuota quota = quotaRepository.findById(studentId)
                    .orElseGet(() -> createDefaultQuota(studentId, now));

            quota.setRemainingUploads(quota.getRemainingUploads() + 1);
            quota.setUpdatedAt(now);
            quotaRepository.save(quota);
            return quota.getRemainingUploads();
        }
    }

    public int getRemainingUploads(Long studentId) {
        SyllabusUploadQuota quota = quotaRepository.findById(studentId)
                .orElseGet(() -> createDefaultQuota(studentId, Instant.now()));
        return quota.getRemainingUploads();
    }

    public int getRemainingUploadsForCurrentStudent() {
        return getRemainingUploads(studentService.getStudentID());
    }

    private SyllabusUploadQuota createDefaultQuota(Long studentId, Instant now) {
        SyllabusUploadQuota quota = new SyllabusUploadQuota();
        quota.setStudentId(studentId);
        quota.setRemainingUploads(DEFAULT_REMAINING_UPLOADS);
        quota.setUpdatedAt(now);
        return quotaRepository.save(quota);
    }
}
