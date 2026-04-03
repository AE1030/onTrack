package org.tracker.gpatracker.courses.dto;

public class CourseLookupDTO {
    private Long id;
    private String courseCode;
    private String courseName;
    private Long courseCredits;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public Long getCourseCredits() {
        return courseCredits;
    }

    public void setCourseCredits(Long courseCredits) {
        this.courseCredits = courseCredits;
    }
}
