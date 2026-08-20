package org.tracker.gpatracker.courses.service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.courses.model.DueDateConsensus;
import org.tracker.gpatracker.courses.model.DueDateOverride;
import org.tracker.gpatracker.courses.repository.DueDateConsensusRepository;
import org.tracker.gpatracker.courses.repository.DueDateOverrideRepository;
import org.tracker.gpatracker.tenancy.TenantScope;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DueDateConsensusService {

    private final DueDateOverrideRepository dueDateOverrideRepository;
    private final DueDateConsensusRepository dueDateConsensusRepository;
    private final TenantScope tenantScope;

    public DueDateConsensusService(DueDateOverrideRepository dueDateOverrideRepository,
                                    DueDateConsensusRepository dueDateConsensusRepository,
                                    TenantScope tenantScope) {
        this.dueDateOverrideRepository = dueDateOverrideRepository;
        this.dueDateConsensusRepository = dueDateConsensusRepository;
        this.tenantScope = tenantScope;
    }

    /**
     * Aggregates every student's proposed due dates into a shared consensus.
     *
     * <p>Genuinely cross-tenant, so it runs unfiltered. This is not optional: with the owner
     * filter enabled and no tenant bound, the {@code findAll} below would see nothing and the
     * {@code votes < 2} branch would then delete existing consensus rows on that basis.
     */
    @Transactional
    @Scheduled(cron = "0 0 */12 * * *")
    public void setConsensusDueDate() {
        tenantScope.unfiltered(this::recomputeConsensusAcrossAllTenants);
    }

    private void recomputeConsensusAcrossAllTenants() {
        List<DueDateOverride> overrides = dueDateOverrideRepository.findAll();
        Map<AssessmentKey, Map<LocalDate, Long>> votesByAssessment = new HashMap<>();

        for (DueDateOverride override : overrides) {
            if (override.getCourseCode() == null || override.getAssessmentName() == null || override.getProposedDueDate() == null) {
                continue;
            }
            AssessmentKey key = new AssessmentKey(override.getCourseCode(), override.getAssessmentName());
            Map<LocalDate, Long> dateVotes = votesByAssessment.computeIfAbsent(key, k -> new HashMap<>());
            dateVotes.merge(override.getProposedDueDate(), 1L, Long::sum);
        }

        for (Map.Entry<AssessmentKey, Map<LocalDate, Long>> entry : votesByAssessment.entrySet()) {
            AssessmentKey key = entry.getKey();
            Map<LocalDate, Long> dateVotes = entry.getValue();

            Optional<Map.Entry<LocalDate, Long>> bestVote = dateVotes.entrySet().stream()
                    .max(Comparator.<Map.Entry<LocalDate, Long>>comparingLong(Map.Entry::getValue)
                            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()));

            if (bestVote.isEmpty() || bestVote.get().getValue() < 2) {
                dueDateConsensusRepository.findByCourseCodeAndAssessmentName(key.courseCode(), key.assessmentName())
                        .ifPresent(dueDateConsensusRepository::delete);
                continue;
            }

            LocalDate consensusDate = bestVote.get().getKey();
            Long votes = bestVote.get().getValue();

            DueDateConsensus consensus = dueDateConsensusRepository
                    .findByCourseCodeAndAssessmentName(key.courseCode(), key.assessmentName())
                    .orElseGet(DueDateConsensus::new);
            consensus.setCourseCode(key.courseCode());
            consensus.setAssessmentName(key.assessmentName());
            consensus.setDueDate(consensusDate);
            consensus.setVotes(votes);
            dueDateConsensusRepository.save(consensus);
        }

    }

    private record AssessmentKey(String courseCode, String assessmentName) { }
}
