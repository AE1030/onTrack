package org.tracker.gpatracker.assessmenttable.model;

import java.util.List;

public class AssessmentScheme {
    private String schemeName;

    private List<SchemeAssessment> assessments;

    public String getSchemeName() {
        return schemeName;
    }

    public void setSchemeName(String schemeName) {
        this.schemeName = schemeName;
    }

    public List<SchemeAssessment> getAssessments() {
        return assessments;
    }

    public void setAssessments(List<SchemeAssessment> assessments) {
        this.assessments = assessments;
    }
}
