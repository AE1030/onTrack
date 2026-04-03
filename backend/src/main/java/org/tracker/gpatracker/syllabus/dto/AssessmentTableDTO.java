package org.tracker.gpatracker.syllabus.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public class AssessmentTableDTO {
    private String assessmentName;
    private BigDecimal[] weights;
    private String dueDate;
    private String startTime;
    private String endTime;
    private String location;
    private BigDecimal grade;
    @JsonProperty("N")
    private long n;

    public long getN() {
        return n;
    }

    public void setN(long n) {
        this.n = n;
    }

    public String getAssessmentName() {
        return assessmentName;
    }

    public void setAssessmentName(String assessmentName) {
        this.assessmentName = assessmentName;
    }

    public BigDecimal[] getWeights() {
        return weights;
    }

    public void setWeights(BigDecimal[] weights) {
        this.weights = weights;
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

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public BigDecimal getGrade() {
        return grade;
    }

    public void setGrade(BigDecimal grade) {
        this.grade = grade;
    }

    @Override
    public String toString() {
        String weightsStr;
        if (weights == null) {
            weightsStr = "null";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < weights.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(weights[i]);
            }
            sb.append("]");
            weightsStr = sb.toString();
        }
        return "AssessmentTableDTO{" +
                "assessmentName='" + assessmentName + '\'' +
                ", weights=" + weightsStr +
                ", dueDate='" + dueDate + '\'' +
                ", startTime='" + startTime + '\'' +
                ", endTime='" + endTime + '\'' +
                ", location='" + location + '\'' +
                ", N=" + n +
                '}';
    }
}
