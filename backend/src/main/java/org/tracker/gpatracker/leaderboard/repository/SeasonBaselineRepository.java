package org.tracker.gpatracker.leaderboard.repository;

import org.tracker.gpatracker.leaderboard.model.SeasonBaseline;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.util.List;
import java.util.Optional;

public interface SeasonBaselineRepository extends UserScopedRepository<SeasonBaseline, Long> {

    Optional<SeasonBaseline> findByOwnerIdAndSeason(Long studentId, String season);

    /** Every student's frozen baseline for a season. Ranking-job only, and only when unfiltered. */
    List<SeasonBaseline> findBySeason(String season);
}
