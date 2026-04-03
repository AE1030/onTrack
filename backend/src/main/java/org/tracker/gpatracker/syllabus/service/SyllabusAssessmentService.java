package org.tracker.gpatracker.syllabus.service;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.syllabus.dto.AssessmentTableDTO;
import org.tracker.gpatracker.syllabus.model.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class SyllabusAssessmentService {

    private final List<SyllabusDocumentProvider> providers;
    private final StudentService studentService;

    public SyllabusAssessmentService(List<SyllabusDocumentProvider> providers,
                                     StudentService studentService) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(SyllabusDocumentProvider::priority))
                .toList();
        this.studentService = studentService;
    }


    public AbstractSyllabusDocument getSyllabusDocument(String courseCode, String term) {
        for (SyllabusDocumentProvider provider : providers) {
           Optional<AbstractSyllabusDocument> doc =
                    provider.findByCourseCodeAndTerm(courseCode, term);
            if (doc.isPresent()) {
                AbstractSyllabusDocument found = doc.get();
                if (found instanceof UserSyllabusDocument userDoc) {
                    Long currentStudentId = studentService.getStudentID();
                    if (currentStudentId == null || !currentStudentId.equals(userDoc.getStudentId())) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Syllabus not found");
                    }
                }
                return found;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Syllabus not found");
    }

    /**
     * Used by /assessments endpoint
     */
    public Map<String, List<AssessmentTableDTO>> getNormalizedAssessmentItems(AbstractSyllabusDocument doc) {
        Assessments assess = doc.getAssessments();
        if (assess == null) {
            return Map.of();
        }

        GradingScheme gradingScheme = assess.getGradingScheme();
        if (gradingScheme == null) {
            return Map.of();
        }

        Map<String, SchemeDefinition> schemes = gradingScheme.getSchemes();
        if (schemes == null || schemes.isEmpty()) {
            return Map.of();
        }

        Map<String, List<AssessmentTableDTO>> expanded = new LinkedHashMap<>();

        for (Map.Entry<String, SchemeDefinition> entry : schemes.entrySet()) {
            String schemeKey = entry.getKey();
            SchemeDefinition scheme = entry.getValue();
            List<AssessmentItem> items = scheme.getAssessmentItemList();
            if (items == null || items.isEmpty()) {
                continue;
            }
            String schemeName = scheme.getLabel();
            if (schemeName == null || schemeName.isBlank()) {
                schemeName = schemeKey;
            }
            List<AssessmentTableDTO> expandedItems = expandOccurrences(items);
            expanded.put(schemeName, expandedItems);
        }

        return expanded;
    }



    /* ====================
       Occurrence Expansion
    ======================= */
    private List<AssessmentTableDTO> expandOccurrences(List<AssessmentItem> raw) {
        List<AssessmentTableDTO> expanded = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return expanded;

        for (AssessmentItem item : raw) {
            expandSingleItem(item, expanded);
        }

        return expanded;
    }

    private void expandSingleItem(AssessmentItem item, List<AssessmentTableDTO> expanded) {
        Occurrence occurrence = item.getOccurrence();
        Integer total = occurrence == null ? null : occurrence.getTotal();
        int occurrences = (total == null || total <= 0) ? 1 : total;

        List<String> dueDates = splitDueDates(item.getDueDate());
        BigDecimal perOccurrenceWeight = computePerOccurrenceWeight(item, total);
        String baseName = resolveBaseName(item);
        ReplacementRule replacementRule = item.getReplacementRule();
        boolean isLowestNDropped = isLowestNDropped(replacementRule);
        int n = (replacementRule == null) ? 0 : replacementRule.getN();
        BigDecimal keptWeight = computeKeptWeight(isLowestNDropped, item, occurrences, n);

        for (int i = 1; i <= occurrences; i++) {
            expanded.add(buildDto(item, i, occurrences, baseName, dueDates,
                    isLowestNDropped, keptWeight, n, perOccurrenceWeight));
        }
    }

    private String resolveBaseName(AssessmentItem item) {
        String name = item.getName();
        if (name != null && !name.isBlank()) return name;
        return item.getCategory() == null || item.getCategory().isBlank() ? "Assessment" : item.getCategory();
    }

    private BigDecimal computePerOccurrenceWeight(AssessmentItem item, Integer total) {
        if (item.getWeight() != null && total != null && total > 0) {
            return item.getWeight().divide(BigDecimal.valueOf(total), 10, RoundingMode.HALF_UP);
        }
        return null;
    }

    private boolean isLowestNDropped(ReplacementRule rule) {
        return rule != null
                && rule.getTriggerType() != null
                && rule.getTriggerType().equalsIgnoreCase("LOWEST_N_DROPPED");
    }

    private BigDecimal computeKeptWeight(boolean isLowestNDropped, AssessmentItem item, int occurrences, int n) {
        if (!isLowestNDropped || item.getWeight() == null) return null;
        int denominator = occurrences - n;
        if (denominator > 0) {
            return item.getWeight().divide(BigDecimal.valueOf(denominator), 10, RoundingMode.HALF_UP);
        }
        return null;
    }

    private AssessmentTableDTO buildDto(AssessmentItem item, int index, int occurrences,
                                         String baseName, List<String> dueDates,
                                         boolean isLowestNDropped, BigDecimal keptWeight,
                                         int n, BigDecimal perOccurrenceWeight) {
        AssessmentTableDTO dto = new AssessmentTableDTO();
        dto.setAssessmentName(occurrences > 1 ? baseName + " #" + index : baseName);
        dto.setDueDate(index - 1 < dueDates.size() ? dueDates.get(index - 1) : item.getDueDate());
        dto.setStartTime(item.getStartTime());
        dto.setEndTime(item.getEndTime());
        dto.setLocation(item.getLocation());

        if (isLowestNDropped) {
            dto.setWeights(new BigDecimal[]{BigDecimal.ZERO, keptWeight});
            dto.setN(n);
        } else {
            BigDecimal singleWeight = perOccurrenceWeight != null ? perOccurrenceWeight : item.getWeight();
            dto.setWeights(new BigDecimal[]{singleWeight});
        }

        return dto;
    }

    private List<String> splitDueDates(String dueDate) {
        List<String> dates = new ArrayList<>();
        if (dueDate == null || dueDate.isBlank()) return dates;

        String[] parts = dueDate.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                dates.add(trimmed);
            }
        }

        return dates;
    }


}
