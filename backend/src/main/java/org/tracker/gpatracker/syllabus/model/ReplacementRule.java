package org.tracker.gpatracker.syllabus.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ReplacementRule {

    // triggerType needs no annotation: the persistence mapper's SNAKE_CASE strategy already renders
    // it as trigger_type, which is what the extractor prompt asks the model for.
    private String triggerType;

    // "N" does, because no naming strategy turns "n" into "N". This was @Field("N") on Mongo and the
    // extractor emits it capitalised, so without this the drop-lowest-N rule would deserialise as
    // zero -- a replacement rule that silently stops replacing anything.
    @JsonProperty("N")
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
