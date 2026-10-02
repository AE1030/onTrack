package org.tracker.gpatracker.assessmenttable.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

public class SchemeAssessment {
    private String name;
    private String description;
    private String location;
    private String dueDate;
    private String startTime;
    private String endTime;
    private BigDecimal weight;

    // Encrypted at rest, but not by an annotation here. This class is also the REST contract --
    // SaveAssessmentTableDTO carries these very objects -- so a serializer attached to the field
    // would encrypt the value on its way to the client too. The encryption lives on the persistence
    // path alone, in AssessmentSchemesJsonConverter, which rewrites this one property as it walks
    // the JSON tree on the way into and out of the jsonb column.
    private BigDecimal grade;

    // ------------------------------------------------------------ server-set evidence
    //
    // The three fields below are the only things on this document the client cannot write. They
    // are what the leaderboard's time-lock and behaviour flags are computed from, and a timestamp
    // the client controls would just be another self-reported field.
    //
    // READ_ONLY means Jackson serialises them out to the client but silently drops them on the way
    // back in, so they cannot arrive on SaveAssessmentTableDTO -- which carries these very objects.
    // GradeStamper then overwrites whatever is on the incoming rows from the stored document, so
    // even a binding change here cannot make a client value stick.

    /** When a grade first appeared on this row. Set once, and carried forward across edits. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Instant firstGradedAt;

    /** When the grade last moved. Updated on every change to the mark. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Instant lastGradedAt;

    /** How often the due date moved <em>after</em> a grade existed. Moving it before is housekeeping. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private int dueDateChangeCount;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getDueDate() {
        return dueDate;
    }

    public void setDueDate(String dueDate) {
        this.dueDate = dueDate;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public BigDecimal getGrade() {
        return grade;
    }

    public void setGrade(BigDecimal grade) {
        this.grade = grade;
    }

    public Instant getFirstGradedAt() {
        return firstGradedAt;
    }

    public void setFirstGradedAt(Instant firstGradedAt) {
        this.firstGradedAt = firstGradedAt;
    }

    public Instant getLastGradedAt() {
        return lastGradedAt;
    }

    public void setLastGradedAt(Instant lastGradedAt) {
        this.lastGradedAt = lastGradedAt;
    }

    public int getDueDateChangeCount() {
        return dueDateChangeCount;
    }

    public void setDueDateChangeCount(int dueDateChangeCount) {
        this.dueDateChangeCount = dueDateChangeCount;
    }
}
