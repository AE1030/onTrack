package org.tracker.gpatracker.syllabus.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.syllabus.exception.SyllabusErrorType;
import org.tracker.gpatracker.syllabus.model.JobStatus;
import org.tracker.gpatracker.syllabus.model.SyllabusExtractionJob;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SyllabusExtractionJobRepository
        extends UserScopedRepository<SyllabusExtractionJob, String> {

    /** Newest first, so the first row seen for a course is its latest job. */
    List<SyllabusExtractionJob> findByTermAndUpdatedAtAfterOrderByCreatedAtDesc(String term, Instant since);

    List<SyllabusExtractionJob> findByOwnerIdAndStatusInAndCreatedAtBefore(Long ownerId,
                                                                           Collection<JobStatus> statuses,
                                                                           Instant cutoff);

    /**
     * Fails the job only if it is still QUEUED or PROCESSING, and returns how many rows changed.
     *
     * <p>The stale sweep and the job's own failure handling can race. Both refund the upload, so
     * each refunds only when this returns 1, and the upload comes back exactly once. The owner is in
     * the where clause explicitly, since Hibernate filters do not apply to bulk updates. Bulk
     * updates also skip JPA auditing, hence the explicit updatedAt.
     *
     * <p>clearAutomatically matters: open-in-view keeps one persistence context per request, so
     * the sweep's own read leaves the job cached as PROCESSING, and without the clear the
     * findById that follows in the same request would hand back that stale copy.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SyllabusExtractionJob j
            set j.status = org.tracker.gpatracker.syllabus.model.JobStatus.FAILED,
                j.errorType = :errorType, j.error = :error, j.updatedAt = :now
            where j.id = :id and j.ownerId = :ownerId
              and j.status in (org.tracker.gpatracker.syllabus.model.JobStatus.QUEUED,
                               org.tracker.gpatracker.syllabus.model.JobStatus.PROCESSING)
            """)
    int failIfActive(@Param("id") String id,
                     @Param("ownerId") Long ownerId,
                     @Param("errorType") SyllabusErrorType errorType,
                     @Param("error") String error,
                     @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SyllabusExtractionJob j set j.remainingUploads = :remaining where j.id = :id and j.ownerId = :ownerId")
    int setRemainingUploads(@Param("id") String id,
                            @Param("ownerId") Long ownerId,
                            @Param("remaining") int remaining);
}
