package org.tracker.gpatracker.syllabus.model;

import org.springframework.data.mongodb.core.mapping.Field;

import java.util.Map;

public class GradingScheme {
    @Field("selection_rule")
    private SelectionRule selectionRule;
    private Map<String,SchemeDefinition> schemes;

    public SelectionRule getSelectionRule() {
        return selectionRule;
    }

    public void setSelectionRule(SelectionRule selectionRule) {
        this.selectionRule = selectionRule;
    }

    public Map<String, SchemeDefinition> getSchemes() {
        return schemes;
    }
    public void setSchemes(Map<String, SchemeDefinition> schemes) {
        this.schemes = schemes;
    }
}
enum SelectionRule {
    MAX
}


