package org.tracker.gpatracker.assessmenttable.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwnedEntity;

import java.util.List;

/**
 * A student's grading table for one course in one term.
 *
 * <p>The unique constraint is not decoration. {@code AssessmentTableService.saveToRepo} reads by
 * (owner, course, term) and then saves, so two concurrent saves for a course with no table yet can
 * both find nothing and both insert. Without the constraint that silently becomes two rows, and
 * {@code findByOwnerIdAndCourseCodeAndTerm} returns an {@code Optional} — so from then on that course
 * throws on every read, while {@code ProjectedGpaService} (which reads a {@code List}) quietly
 * weights the course twice. With it, the losing write is rejected at once and
 * {@code insertOrMerge} recovers. It is declared both here and in {@code V16}, so
 * {@code ddl-auto=validate} confirms the two agree.
 *
 * <p>{@code schemes} goes through {@link AssessmentSchemesJsonConverter} rather than a plain JSON
 * mapping, because each assessment's grade is encrypted inside the payload. See that class for why
 * it cannot be a Jackson annotation on the field.
 */
@Entity
@Table(
        name = "assessment_table",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_assessment_student_course_term",
                columnNames = {"student_id", "course_code", "term"}))
@Filter(name = OwnerFilter.NAME)
public class AssessmentTableDocument extends UserOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "course_code", nullable = false, length = 64)
    private String courseCode;

    @Column(name = "term", nullable = false, length = 64)
    private String term;

    @Convert(converter = AssessmentSchemesJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schemes")
    private List<AssessmentScheme> schemes;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public List<AssessmentScheme> getSchemes() {
        return schemes;
    }

    public void setSchemes(List<AssessmentScheme> schemes) {
        this.schemes = schemes;
    }
}
