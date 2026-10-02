package org.tracker.gpatracker.assessmenttable.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;
import org.tracker.gpatracker.accounts.service.BigDecimalGradeEncryptionConverter;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stores the scheme list as jsonb, with each assessment's grade encrypted inside the JSON.
 *
 * <p>This exists because a plain {@code @JdbcTypeCode(SqlTypes.JSON)} mapping would be a security
 * regression. On Mongo the grade was encrypted by a {@code @ValueConverter} on
 * {@link SchemeAssessment#getGrade()}, which Spring Data applied on the way to the collection.
 * Jackson knows nothing about that annotation, so mapping the list straight to jsonb would write
 * every student's marks in plaintext.
 *
 * <p>It cannot be done with Jackson annotations on the field either. {@link SchemeAssessment} is
 * also the REST contract — {@code SaveAssessmentTableDTO} carries these very objects — so a
 * {@code @JsonSerialize} on {@code grade} would encrypt the value on its way to the client too.
 * Encryption has to attach to the persistence path alone, which is what a JPA converter is.
 *
 * <p>The transformation is done on the tree rather than through an annotated serializer because the
 * shape is known and shallow ({@code schemes[].assessments[].grade}), and walking it explicitly is
 * easier to read and to test than a Jackson handler wired in through a module.
 *
 * <p>Two mapper settings are load-bearing:
 *
 * <ul>
 *   <li><b>Access is forced to {@code AUTO}.</b> {@code firstGradedAt}, {@code lastGradedAt} and
 *       {@code dueDateChangeCount} are {@code READ_ONLY}, which is right for the API — the client
 *       must not be able to set them — but READ_ONLY also means "ignore on deserialize". Left alone,
 *       every load from the database would drop all three, and {@code GradeStamper}'s carry-forward
 *       would silently reset each time a table was saved. Access control is an API concern, not a
 *       storage one.
 *   <li><b>Dates are written as ISO strings.</b> Readable in {@code psql}, and stable if the
 *       Jackson default for timestamps ever changes.
 * </ul>
 *
 * <p>Property naming stays camelCase, unlike {@code SyllabusAssessmentsJsonConverter}. This payload
 * is written and read only by this application, so there is no cross-language contract to match.
 */
@Converter
@Component
public class AssessmentSchemesJsonConverter
        implements AttributeConverter<List<AssessmentScheme>, String> {

    private static final TypeReference<List<AssessmentScheme>> SCHEME_LIST = new TypeReference<>() {
    };

    private static final String ASSESSMENTS = "assessments";
    private static final String GRADE = "grade";

    private final ObjectMapper mapper;
    private final BigDecimalGradeEncryptionConverter cipher;

    /**
     * Hibernate resolves converters through Spring's {@code SpringBeanContainer}, so the cipher
     * arrives by injection rather than being rebuilt from the classpath. There is deliberately no
     * no-arg fallback: the alternative path in {@code BigDecimalGradeEncryptionConverter} reads the
     * key straight out of {@code application.properties} and resolves {@code ${GRADE_ENCRYPTION_KEY}}
     * from the OS environment, which does not exist under the test profile or under a {@code .env}.
     * Failing to construct is better than constructing with the wrong key.
     */
    public AssessmentSchemesJsonConverter(BigDecimalGradeEncryptionConverter cipher) {
        this.cipher = cipher;
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .annotationIntrospector(new JacksonAnnotationIntrospector() {
                    @Override
                    public JsonProperty.Access findPropertyAccess(Annotated annotated) {
                        return JsonProperty.Access.AUTO;
                    }
                })
                .build();
    }

    @Override
    public String convertToDatabaseColumn(List<AssessmentScheme> schemes) {
        if (schemes == null) {
            return null;
        }
        JsonNode root = mapper.valueToTree(schemes);
        forEachAssessment(root, assessment -> {
            JsonNode grade = assessment.get(GRADE);
            if (grade != null && !grade.isNull()) {
                assessment.set(GRADE, TextNode.valueOf(cipher.encryptBigDecimal(grade.decimalValue())));
            }
        });
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise assessment schemes", e);
        }
    }

    @Override
    public List<AssessmentScheme> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read assessment schemes json", e);
        }
        forEachAssessment(root, assessment -> {
            JsonNode grade = assessment.get(GRADE);
            // Textual means encrypted. A number would mean a row written before this converter
            // existed, which cannot happen here -- but decrypting one would throw, and leaving it
            // alone is both safe and correct.
            if (grade != null && grade.isTextual()) {
                BigDecimal decrypted = cipher.decryptToBigDecimal(grade.asText());
                assessment.set(GRADE, decrypted == null ? null : DecimalNode.valueOf(decrypted));
            }
        });
        return mapper.convertValue(root, SCHEME_LIST);
    }

    /** Visits every assessment object in {@code schemes[].assessments[]}, tolerating gaps. */
    private static void forEachAssessment(JsonNode root, Consumer<ObjectNode> visitor) {
        if (root == null || !root.isArray()) {
            return;
        }
        for (JsonNode scheme : root) {
            JsonNode assessments = scheme.get(ASSESSMENTS);
            if (assessments == null || !assessments.isArray()) {
                continue;
            }
            for (JsonNode assessment : assessments) {
                if (assessment instanceof ObjectNode objectNode) {
                    visitor.accept(objectNode);
                }
            }
        }
    }
}
