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
import org.tracker.gpatracker.tenancy.BaseEntity;
import org.tracker.gpatracker.tenancy.UserOwned;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One student's published standing for one season.
 *
 * <p><b>Deliberately not {@link UserOwned}, and deliberately not filtered.</b> A leaderboard that
 * only shows you your own row is not a leaderboard. This is the second table in the codebase to
 * take that exception, after {@code SyllabusDocument}, and it is safe for the same reason: nothing
 * on it is private. Every column is either derived ({@code score}, {@code ambition}, the behaviour
 * figures) or was explicitly chosen for publication ({@code handle}). The values that genuinely are
 * grades — the baseline and the target — live on {@code SeasonBaseline}, which is filtered.
 *
 * <p>{@code studentId} is a plain column here rather than the inherited {@code ownerId}, because
 * inheriting it would mean inheriting the filter contract this table is exempt from.
 * {@code TenantMappingTest} names this class explicitly as the exception, so the exemption is
 * asserted rather than assumed.
 *
 * <p>Rewritten in place by the ranking job, three times a day. Nothing on a request path writes it.
 */
@Entity
@Table(
        name = "leaderboard_entry",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_leaderboard_entry_student_season",
                columnNames = {"student_id", "season"}
        )
)
public class LeaderboardEntry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false, updatable = false)
    private Long studentId;

    @Column(name = "season", nullable = false, length = 64)
    private String season;

    /** Denormalised from {@code LeaderboardProfile} at rank time, so a public read joins nothing. */
    @Column(name = "handle", nullable = false, length = 24)
    private String handle;

    /** Denormalised from {@code LeaderboardProfile} alongside the handle. Null when never chosen. */
    @Convert(converter = LeaderboardAvatarConverter.class)
    @Column(name = "avatar", length = 32)
    private LeaderboardAvatar avatar;

    /**
     * Plaintext on purpose — see the migration. It is a derived progress ratio rather than a grade,
     * which is what lets the board be ranked in SQL without weakening the encryption story.
     *
     * <p>Ranges 0 to 12,250, not 0 to 10,000: a delivered ambitious goal pays a bonus.
     */
    @Column(name = "score", nullable = false, precision = 8, scale = 2)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EntryStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "score_mode", nullable = false, length = 20)
    private ScoreMode scoreMode;

    @Column(name = "ambition", nullable = false, precision = 4, scale = 3)
    private BigDecimal ambition;

    @Column(name = "behavior_score", nullable = false, precision = 4, scale = 3)
    private BigDecimal behaviorScore;

    @Column(name = "behavior_flags", nullable = false)
    private int behaviorFlags;

    /** When the ranking job last touched this row. Lets the board say how fresh it is. */
    @Column(name = "computed_at")
    private Instant computedAt;

    /**
     * Position on the board after the most recent run, 1 being first.
     *
     * <p>Duplicates what {@code LeaderboardService.board()} derives at read time, on purpose: the
     * read is the live truth, and this is the copy the next run compares against. Null until the
     * job has ranked this entry once.
     */
    @Column(name = "rank")
    private Integer rank;

    /**
     * Where this entry sat after the run before last. Null when there is no earlier run, which is
     * the difference between "held position" and "we do not know yet".
     */
    @Column(name = "previous_rank")
    private Integer previousRank;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public String getSeason() {
        return season;
    }

    public void setSeason(String season) {
        this.season = season;
    }

    public String getHandle() {
        return handle;
    }

    public void setHandle(String handle) {
        this.handle = handle;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public EntryStatus getStatus() {
        return status;
    }

    public void setStatus(EntryStatus status) {
        this.status = status;
    }

    public ScoreMode getScoreMode() {
        return scoreMode;
    }

    public void setScoreMode(ScoreMode scoreMode) {
        this.scoreMode = scoreMode;
    }

    public BigDecimal getAmbition() {
        return ambition;
    }

    public void setAmbition(BigDecimal ambition) {
        this.ambition = ambition;
    }

    public BigDecimal getBehaviorScore() {
        return behaviorScore;
    }

    public void setBehaviorScore(BigDecimal behaviorScore) {
        this.behaviorScore = behaviorScore;
    }

    public int getBehaviorFlags() {
        return behaviorFlags;
    }

    public void setBehaviorFlags(int behaviorFlags) {
        this.behaviorFlags = behaviorFlags;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(Instant computedAt) {
        this.computedAt = computedAt;
    }

    public LeaderboardAvatar getAvatar() {
        return avatar;
    }

    public void setAvatar(LeaderboardAvatar avatar) {
        this.avatar = avatar;
    }

    public Integer getRank() {
        return rank;
    }

    public void setRank(Integer rank) {
        this.rank = rank;
    }

    public Integer getPreviousRank() {
        return previousRank;
    }

    public void setPreviousRank(Integer previousRank) {
        this.previousRank = previousRank;
    }

    /**
     * Places moved since the previous run, positive meaning climbed, or null when there is nothing
     * to compare against.
     *
     * <p>Ranks count down, so the subtraction reads backwards from what it looks like: 12th to 9th
     * is a gain of three. Deliberately not a score delta — everyone else moves too, and a student
     * can gain points while sliding down the board.
     */
    public Integer rankDelta() {
        if (rank == null || previousRank == null) {
            return null;
        }
        return previousRank - rank;
    }
}
