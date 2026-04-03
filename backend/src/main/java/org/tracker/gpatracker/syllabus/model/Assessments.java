package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.mongodb.core.mapping.Field;

public class Assessments {
    @Field("grading_scheme")
    private GradingScheme gradingScheme;

    public GradingScheme getGradingScheme() {
        return gradingScheme;
    }

    public void setGradingScheme(GradingScheme gradingScheme) {
        this.gradingScheme = gradingScheme;
    }


}
