package org.tracker.gpatracker.terms;

/**
 * One entry in the term picker.
 *
 * <p>{@code editable} is the same boolean as {@code current} today, and is sent as its own field
 * anyway. The client should ask "may I edit this" rather than infer it from "is this the current
 * term", so the day a second editable term exists nothing in the frontend has to change.
 */
public class TermDTO {

    private String term;
    private boolean current;
    private boolean editable;
    private int courseCount;

    public TermDTO() {
    }

    public TermDTO(String term, boolean current, boolean editable, int courseCount) {
        this.term = term;
        this.current = current;
        this.editable = editable;
        this.courseCount = courseCount;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }

    public boolean isEditable() {
        return editable;
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
    }

    public int getCourseCount() {
        return courseCount;
    }

    public void setCourseCount(int courseCount) {
        this.courseCount = courseCount;
    }
}
