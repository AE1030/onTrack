package org.tracker.gpatracker.leaderboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.tracker.gpatracker.tenancy.UserOwned;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Where one student stood after one scoring run.
 *
 * <p>Appended rather than overwritten, which is the entire difference between this and
 * {@link LeaderboardEntry}. The entry answers "where is everyone now" and is rewritten in place;
 * this answers "where was this student before" and only ever grows, so the sparkline has more than
 * a single previous point to draw.
 *
 * <p><b>Not {@link UserOwned}</b>, following {@code LeaderboardEntry} for the same reason: it holds
 * a rank and a score, both already published on the board, and no grade. {@code TenantMappingTest}
 * names it alongside the entry as a deliberate exception.
 *
 * <p>Does not extend {@code BaseEntity}. Its audit columns would duplicate {@code computedAt},
 * which is the only timestamp that means anything here: rows are written by a batch job and never
 * updated, so "when was this modified" has no second answer.
 */
@Entity
@Table(
        name = "leaderboard_rank_history",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rank_history_student_season_run",
                columnNames = {"student_id", "season", "computed_at"}
        )
)
public class LeaderboardRankHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false, updatable = false)
    private Long studentId;

    @Column(name = "season", nullable = false, length = 64, updatable = false)
    private String season;

    @Column(name = "rank", nullable = false, updatable = false)
    private int rank;

    /** Carried alongside the rank so a season can be reviewed without joining back to the entry. */
    @Column(name = "score", nullable = false, precision = 8, scale = 2, updatable = false)
    private BigDecimal score;

    /**
     * The run this sample belongs to, shared by every row the same sweep writes.
     *
     * <p>Also the de-duplication key: the unique constraint on
     * {@code (student_id, season, computed_at)} is what stops a retried or doubly-triggered run
     * from putting two identical points on one student's line.
     */
    @Column(name = "computed_at", nullable = false, updatable = false)
    private Instant computedAt;

    protected LeaderboardRankHistory() {
        // for JPA
    }

    public LeaderboardRankHistory(Long studentId, String season, int rank, BigDecimal score,
                                  Instant computedAt) {
        this.studentId = studentId;
        this.season = season;
        this.rank = rank;
        this.score = score;
        this.computedAt = computedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getStudentId() {
        return studentId;
    }

    public String getSeason() {
        return season;
    }

    public int getRank() {
        return rank;
    }

    public BigDecimal getScore() {
        return score;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
