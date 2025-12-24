package org.tracker.gpatracker.model;

import jakarta.persistence.*;

import java.util.Set;

@Entity
public class Course {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String courseCode;
    private String courseName;
    private Long courseCredits;
    public Course() {
    }

    //Relationship between student and course
    //CourseEnrollement entity now owns this relationship which started off as a many-to-many relationship
    //from the CourseEnrollement perspective this is a many to one relationship so we have to inverse it here
    //neither course nor student is the owning side
    @OneToMany(mappedBy= "students")
    Set<CourseEnrollement> enroll;

    public Course(Long id, String courseCode, String courseName, Long courseCredits) {
        this.id = id;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.courseCredits = courseCredits;
    }

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
