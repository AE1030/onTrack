package org.tracker.gpatracker.leaderboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Filter;
import org.tracker.gpatracker.accounts.service.BigDecimalGradeEncryptionConverter;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwnedEntity;

import java.math.BigDecimal;

/**
 * Where a student started and what they said they were aiming for, frozen at the moment they
 * joined a season.
 *
 * <p>Frozen, not read live. If scoring read {@code Student.targetGpa12} directly, lowering the
 * target in week 11 would inflate progress retroactively; if a mid-term transcript re-upload could
 * rewrite the baseline, dropping it would do the same from the other end. The unique constraint on
 * {@code (student_id, season)} is what makes all three values write-once — but only if the
 * onboarding code inserts-if-absent rather than upserting, or the constraint is never consulted.
 *
 * <p>Both GPAs are encrypted, because unlike {@code LeaderboardEntry.score} they genuinely are
 * grades. The mode is stored rather than re-derived so that a later change to the thresholds
 * cannot silently rescore a season that has already been played.
 */
@Entity
// Must be declared here, on the concrete entity: Hibernate does not inherit @Filter from a
// @MappedSuperclass, and omitting it leaves the table silently unfiltered.
@Filter(name = OwnerFilter.NAME)
@Table(
        name = "season_baseline",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_season_baseline_student_season",
                columnNames = {"student_id", "season"}
        )
)
public class SeasonBaseline extends UserOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The owner lives in UserOwnedEntity.ownerId, mapped to the same student_id column.

    @Column(name = "season", nullable = false, length = 64)
    private String season;

    /** The transcript GPA at join time. Never overwritten, not even by a later upload. */
    @Convert(converter = BigDecimalGradeEncryptionConverter.class)
    @Column(name = "baseline_gpa12", nullable = false, columnDefinition = "text")
    private BigDecimal baselineGpa12;

    /** The declared goal. Mandatory: a season with no target has nothing to measure progress against. */
    @Convert(converter = BigDecimalGradeEncryptionConverter.class)
    @Column(name = "target_gpa12", nullable = false, columnDefinition = "text")
    private BigDecimal targetGpa12;

    @Enumerated(EnumType.STRING)
    @Column(name = "score_mode", nullable = false, length = 20)
    private ScoreMode scoreMode;

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

    public BigDecimal getBaselineGpa12() {
        return baselineGpa12;
    }

    public void setBaselineGpa12(BigDecimal baselineGpa12) {
        this.baselineGpa12 = baselineGpa12;
    }

    public BigDecimal getTargetGpa12() {
        return targetGpa12;
    }

    public void setTargetGpa12(BigDecimal targetGpa12) {
        this.targetGpa12 = targetGpa12;
    }

    public ScoreMode getScoreMode() {
        return scoreMode;
    }

    public void setScoreMode(ScoreMode scoreMode) {
        this.scoreMode = scoreMode;
    }
}
