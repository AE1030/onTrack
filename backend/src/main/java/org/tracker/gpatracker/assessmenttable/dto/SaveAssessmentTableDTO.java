package org.tracker.gpatracker.assessmenttable.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;

import java.math.BigDecimal;
import java.util.List;

public class SaveAssessmentTableDTO {
    private String id;

    @NotBlank(message = "Course code is required")
    @Size(max = 20, message = "Course code is too long")
    private String courseCode;

    /**
     * The term the client believes it is editing.
     *
     * <p>This is not how the server chooses where to write. It writes to the current term or it
     * writes nowhere. The field exists so a stale screen, a queued offline mutation or a hand
     * crafted request naming a past term is caught and refused with a 409 rather than silently
     * overwriting the current term's table.
     */
    @NotBlank(message = "Term is required")
    @Size(max = 32, message = "Term is too long")
    private String term;

    private List<AssessmentScheme> schemes;

    /**
     * The course grade this table works out to. Null means "unchanged" — the client
     * omits it when the recomputed grade matches what it already had, so an edit that
     * moves a due date but not a mark never touches Postgres.
     */
    @DecimalMin(value = "0", message = "Grade cannot be negative")
    @DecimalMax(value = "100", message = "Grade cannot exceed 100")
    private BigDecimal grade;

    public BigDecimal getGrade() {
        return grade;
    }

    public void setGrade(BigDecimal grade) {
        this.grade = grade;
    }

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
