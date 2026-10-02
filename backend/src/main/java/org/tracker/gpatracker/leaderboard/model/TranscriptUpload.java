package org.tracker.gpatracker.leaderboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.tracker.gpatracker.accounts.service.BigDecimalGradeEncryptionConverter;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwnedEntity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The transcript a student joined a season with, recorded by hash.
 *
 * <p>This is the only anti-fraud control v2 ships, and it survives the cut because it costs
 * nothing: the upload happens at onboarding anyway, and refusing at the door needs no status
 * machine behind it. If the same PDF turns up under a second account, that join is blocked.
 *
 * <p>The row is tenant-scoped like any other, but the duplicate check that gives it its purpose is
 * inherently cross-tenant and runs under {@code TenantScope.unfiltered} — the filter would
 * otherwise hide precisely the row being looked for.
 */
@Entity
// Must be declared here, on the concrete entity: Hibernate does not inherit @Filter from a
// @MappedSuperclass, and omitting it leaves the table silently unfiltered.
@Filter(name = OwnerFilter.NAME)
@Table(name = "transcript_upload")
public class TranscriptUpload extends UserOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The owner lives in UserOwnedEntity.ownerId, mapped to the same student_id column.

    @Column(name = "season", nullable = false, length = 64)
    private String season;

    /** SHA-256 of the uploaded bytes, lowercase hex. Not a grade, and has to be searchable. */
    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Convert(converter = BigDecimalGradeEncryptionConverter.class)
    @Column(name = "parsed_gpa12", nullable = false, columnDefinition = "text")
    private BigDecimal parsedGpa12;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSeason() {
        return season;
    }

    public void setSeason(String season) {
        this.season = season;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public void setFileSha256(String fileSha256) {
        this.fileSha256 = fileSha256;
    }

    public BigDecimal getParsedGpa12() {
        return parsedGpa12;
    }

    public void setParsedGpa12(BigDecimal parsedGpa12) {
        this.parsedGpa12 = parsedGpa12;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
