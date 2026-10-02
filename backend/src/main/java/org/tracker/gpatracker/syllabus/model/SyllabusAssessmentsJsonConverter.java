package org.tracker.gpatracker.syllabus.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores the extracted grading scheme as jsonb, in snake_case.
 *
 * <p>The naming strategy is the whole point of this class, and it is a contract rather than a
 * preference. This payload has two writers in two languages:
 *
 * <ul>
 *   <li>{@code tools/syllabus-pipeline} writes the shared catalog, and its extractor prompt asks the
 *       model for snake_case keys ({@code due_date}, {@code grading_scheme}, {@code selection_rule}).
 *   <li>{@code GeminiSyllabusExtractionService} writes a student's own extraction, and already parses
 *       the model's reply with {@code PropertyNamingStrategies.SNAKE_CASE} for the same reason.
 * </ul>
 *
 * <p>On Mongo the mapping was carried by {@code @Field("due_date")} annotations scattered across
 * {@link AssessmentItem} and friends. Jackson does not read those, so a default mapper would look
 * for {@code dueDate}, find {@code due_date}, and quietly deserialise every one of those properties
 * as null — an extraction that looks present and is empty where it matters.
 *
 * <p>{@code SchemeDefinition.assessmentItemList} keeps its explicit {@code @JsonProperty}, which
 * wins over the strategy, because {@code assessment_item_list} is not what either writer emits.
 *
 * <p>Deliberately dependency-free and therefore not a Spring bean. Hibernate can instantiate it
 * reflectively wherever it likes, with no chance of picking up a half-configured copy.
 */
@Converter
public class SyllabusAssessmentsJsonConverter implements AttributeConverter<Assessments, String> {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Override
    public String convertToDatabaseColumn(Assessments assessments) {
        if (assessments == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(assessments);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise syllabus assessments", e);
        }
    }

    @Override
    public Assessments convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Assessments.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read syllabus assessments json", e);
        }
    }
}
