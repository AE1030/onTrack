package org.tracker.gpatracker.syllabus.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SchemeDefinition {
    private String label;
    @JsonProperty("assessments")
    private List<AssessmentItem> assessmentItemList;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public List<AssessmentItem> getAssessmentItemList() {
        return assessmentItemList;
    }

    public void setAssessmentItemList(List<AssessmentItem> assessmentItemList) {
        this.assessmentItemList = assessmentItemList;
    }
}
