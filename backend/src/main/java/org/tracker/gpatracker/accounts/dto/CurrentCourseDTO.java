package org.tracker.gpatracker.accounts.dto;

import java.math.BigDecimal;

public class CurrentCourseDTO {
    String courseName;
    String credits;
    BigDecimal grade;
    String term;
    String courseCode;
    boolean includeInGpa;

    /**
     * Whether this course may be edited. False for every course in a past term.
     *
     * <p>Sent as its own field rather than left for the client to work out from the term, so the
     * UI asks "may I edit this" instead of reimplementing the rule.
     */
    boolean editable;

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getCredits() {
        return credits;
    }

    public void setCredits(String credits) {
        this.credits = credits;
    }

    public BigDecimal getGrade() {
        return grade;
    }

    public void setGrade(BigDecimal grade) {
        this.grade = grade;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public boolean isIncludeInGpa() {
        return includeInGpa;
    }

    public void setIncludeInGpa(boolean includeInGpa) {
        this.includeInGpa = includeInGpa;
    }

    public boolean isEditable() {
        return editable;
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
    }
}
