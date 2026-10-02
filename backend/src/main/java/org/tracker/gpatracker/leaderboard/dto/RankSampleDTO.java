package org.tracker.gpatracker.leaderboard.dto;

import java.time.Instant;

/**
 * One point on the caller's rank sparkline.
 *
 * <p>Carries no score. The line plots position over time, and adding a second series to a chart
 * 72 pixels wide would make it unreadable rather than more informative.
 *
 * @param rank       where the student sat after that run, 1 being first
 * @param computedAt which run this point belongs to, and what orders the series
 */
public record RankSampleDTO(int rank, Instant computedAt) {
}
