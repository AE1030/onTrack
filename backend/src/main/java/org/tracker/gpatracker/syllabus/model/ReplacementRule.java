package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.mongodb.core.mapping.Field;

public class ReplacementRule {
    @Field("trigger_type")
    private String triggerType;
    @Field("N")
    private int n;
    public String getTriggerType() {
        return triggerType;
    }
    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }
    public int getN() {
        return n;
    }
    public void setN(int n) {
        this.n = n;
    }
}
