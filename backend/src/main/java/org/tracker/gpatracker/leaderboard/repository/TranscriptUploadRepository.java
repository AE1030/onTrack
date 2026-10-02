package org.tracker.gpatracker.leaderboard.repository;

import org.tracker.gpatracker.leaderboard.model.TranscriptUpload;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.util.List;
import java.util.Optional;

public interface TranscriptUploadRepository extends UserScopedRepository<TranscriptUpload, Long> {

    /** The most recent transcript this student submitted for a season. */
    Optional<TranscriptUpload> findFirstByOwnerIdAndSeasonOrderByIdDesc(Long studentId, String season);


    /**
     * Every account that has ever joined with this PDF.
     *
     * <p>Inherently cross-tenant — the whole question being asked is "has someone else used this
     * file" — so it is only correct under {@code TenantScope.unfiltered}. Called with the filter
     * on, it can only see the caller's own uploads and would wave every duplicate through.
     */
    List<TranscriptUpload> findByFileSha256(String fileSha256);
}
