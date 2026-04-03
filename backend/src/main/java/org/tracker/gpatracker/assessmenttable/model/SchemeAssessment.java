package org.tracker.gpatracker.assessmenttable.model;

import org.springframework.data.convert.ValueConverter;
import org.tracker.gpatracker.accounts.service.BigDecimalGradeEncryptionConverter;

import java.math.BigDecimal;

public class SchemeAssessment {
    private String name;
    private String description;
    private String location;
    private String dueDate;
    private String startTime;
    private String endTime;
    private BigDecimal weight;
    @ValueConverter(BigDecimalGradeEncryptionConverter.class)
    private BigDecimal grade;

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
}
