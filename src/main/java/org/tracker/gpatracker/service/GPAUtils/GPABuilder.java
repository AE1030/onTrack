package org.tracker.gpatracker.service.GPAUtils;

public class GPABuilder {
    private String code = "Unrecognized Course";
    private String units;
    private String grade;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getUnits() { return units; }
    public void setUnits(String units) { this.units = units; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    @Override//for testing purposes only
    public String toString() {
        return "CourseCode=" + this.code + " Grade=" + this.grade + " Units=" +this.units;
    }
}
