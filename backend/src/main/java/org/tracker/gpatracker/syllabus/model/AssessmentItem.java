package org.tracker.gpatracker.syllabus.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;

public class AssessmentItem{

    private String name;
    private String category;

    @Field("due_date")
    @JsonDeserialize(using = DueDateDeserializer.class)
    private String dueDate;   // ISO string or "TBD"
    private BigDecimal weight;    // nullable by design

    @Field("start_time")
    private String startTime; // HH:MM or null
    @Field("end_time")
    private String endTime;   // HH:MM or null
    private String location;
    private String description;
    @Field("bonus_assessment")
    private Boolean bonusAssessment;
    @Field("bonus_description")
    private String bonusDescription;
    @Field("replacement_rule")
    private ReplacementRule replacementRule;
    private Occurrence occurrence;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDueDate() {
        return dueDate;
    }

    public void setDueDate(String dueDate) {
        this.dueDate = dueDate;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
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

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getBonusAssessment() {
        return bonusAssessment;
    }

    public void setBonusAssessment(Boolean bonusAssessment) {
        this.bonusAssessment = bonusAssessment;
    }

    public String getBonusDescription() {
        return bonusDescription;
    }

    public void setBonusDescription(String bonusDescription) {
        this.bonusDescription = bonusDescription;
    }

    public ReplacementRule getReplacementRule() {
        return replacementRule;
    }

    public void setReplacementRule(ReplacementRule replacementRule) {
        this.replacementRule = replacementRule;
    }

    public Occurrence getOccurrence() {
        return occurrence;
    }

    public void setOccurrence(Occurrence occurrence) {
        this.occurrence = occurrence;
    }
}
