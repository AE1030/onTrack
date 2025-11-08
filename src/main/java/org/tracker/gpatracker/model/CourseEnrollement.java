package org.tracker.gpatracker.model;

import jakarta.persistence.*;

@Entity
public class CourseEnrollement {
    @EmbeddedId
    CourseEnrollementKey id;

    //Relationships become many to one as we are looking at it from the perspective of course enrollement
    @ManyToOne
    @MapsId("studentId")//@MapsId means that we tie those fields to a part of the key, and they’re the foreign keys of a many-to-one relationship.
    @JoinColumn(name = "student_id")
    private Student courses;

    @ManyToOne
    @MapsId("courseId")
    @JoinColumn(name = "course_id")
    private Course students;

    //add additional attributes if needed
    public CourseEnrollement() {
    }

    public CourseEnrollementKey getId() {
        return id;
    }

    public void setId(CourseEnrollementKey id) {
        this.id = id;
    }

    public Student getCourses() {
        return courses;
    }

    public void setCourses(Student courses) {
        this.courses = courses;
    }

    public Course getStudents() {
        return students;
    }

    public void setStudents(Course students) {
        this.students = students;
    }
}
