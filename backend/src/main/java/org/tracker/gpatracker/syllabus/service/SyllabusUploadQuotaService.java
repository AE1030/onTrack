package org.tracker.gpatracker.syllabus.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.syllabus.model.SyllabusUploadQuota;
import org.tracker.gpatracker.syllabus.repository.SyllabusUploadQuotaRepository;
import org.tracker.gpatracker.terms.TermService;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Syllabus uploads allowed per student per term.
 *
 * <p>Each row counts uploads against the term it was last used in. When {@code app.current-term}
 * moves on, the stored term no longer matches and the count starts again from zero, so the
 * allowance renews with every term without a scheduled reset.
 */
@Service
public class SyllabusUploadQuotaService {
    static final int UPLOADS_PER_TERM = 3;
    private static final Duration MIN_UPLOAD_INTERVAL = Duration.ofSeconds(20);

    private final ConcurrentHashMap<Long, Object> studentLocks = new ConcurrentHashMap<>();

    private final SyllabusUploadQuotaRepository quotaRepository;
    private final StudentService studentService;
    private final TermService termService;

    public SyllabusUploadQuotaService(SyllabusUploadQuotaRepository quotaRepository,
                                      StudentService studentService,
                                      TermService termService) {
        this.quotaRepository = quotaRepository;
        this.studentService = studentService;
        this.termService = termService;
    }

    /**
     * Atomically checks quota and consumes one upload in a single operation.
     * Returns the remaining uploads after consumption.
     */
    public int consumeUploadQuota(Long studentId) {
        Object lock = studentLocks.computeIfAbsent(studentId, k -> new Object());
        synchronized (lock) {
            Instant now = Instant.now();
            SyllabusUploadQuota quota = quotaForCurrentTerm(studentId);

            if (quota.getUploadsUsed() >= UPLOADS_PER_TERM) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Upload quota exhausted.");
            }

            Instant lastUploadAt = quota.getLastUploadAt();
            if (lastUploadAt != null) {
                Duration sinceLast = Duration.between(lastUploadAt, now);
                if (sinceLast.compareTo(MIN_UPLOAD_INTERVAL) < 0) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Upload rate limit exceeded.");
                }
            }

            quota.setUploadsUsed(quota.getUploadsUsed() + 1);
            quota.setLastUploadAt(now);
            quotaRepository.save(quota);
            return UPLOADS_PER_TERM - quota.getUploadsUsed();
        }
    }

    /**
     * Restores one upload quota unit (e.g. after a non-chargeable failure).
     *
     * <p>If the term rolled over between the charge and the refund, the count has already been
     * reset, and it stays at zero rather than going negative.
     */
    public int restoreQuota(Long studentId) {
        Object lock = studentLocks.computeIfAbsent(studentId, k -> new Object());
        synchronized (lock) {
            SyllabusUploadQuota quota = quotaForCurrentTerm(studentId);
            quota.setUploadsUsed(Math.max(0, quota.getUploadsUsed() - 1));
            quotaRepository.save(quota);
            return UPLOADS_PER_TERM - quota.getUploadsUsed();
        }
    }

    /** Read only: a student with no row, or a row from an earlier term, has the full allowance. */
    public int getRemainingUploads(Long studentId) {
        String currentTerm = termService.getCurrentTerm();
        return quotaRepository.findByOwnerId(studentId)
                .filter(quota -> currentTerm.equals(quota.getTerm()))
                .map(quota -> Math.max(0, UPLOADS_PER_TERM - quota.getUploadsUsed()))
                .orElse(UPLOADS_PER_TERM);
    }

    public int getRemainingUploadsForCurrentStudent() {
        return getRemainingUploads(studentService.getStudentID());
    }

    /**
     * The student's row, created if missing and reset if it still counts an earlier term. Not
     * saved here; every caller saves after its own change.
     */
    private SyllabusUploadQuota quotaForCurrentTerm(Long studentId) {
        String currentTerm = termService.getCurrentTerm();
        Optional<SyllabusUploadQuota> existing = quotaRepository.findByOwnerId(studentId);
        SyllabusUploadQuota quota = existing.orElseGet(() -> {
            SyllabusUploadQuota created = new SyllabusUploadQuota();
            created.setOwnerId(studentId);
            return created;
        });
        if (!currentTerm.equals(quota.getTerm())) {
            quota.setTerm(currentTerm);
            quota.setUploadsUsed(0);
        }
        return quota;
    }
}
