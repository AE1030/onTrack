package org.tracker.gpatracker.model;

import jakarta.persistence.*;

import java.util.Set;

@Entity
public class Student {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id;
    float gpa;
    float targetGpa;

    //Relationship between users and student, the student is the owning side of the relationship
    @OneToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id")//because I didn't name the column user_id in the Users class I have to specify it here
    private Users user;

    //Relationship between students and courses, started off as: many students in enroll in many courses (also students can enroll in the SAME course)
    //CourseEnrollement entity now owns this relationship which started off as a many-to-many relationship
    //from the CourseEnrollement perspective this is a many to one relationhip so we have to inverse it here
    //neither course nor student is the owning side
    @OneToMany(mappedBy = "courses")
    Set <CourseEnrollement> enroll;


    //required no args constructor by the entity annotation
    public Student() {
    }
    public Student(long id, float gpa, float targetGpa, Users user) {
        this.id = id;
        this.gpa = gpa;
        this.targetGpa = targetGpa;
        this.user = user;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public float getGpa() {
        return gpa;
    }

    public void setGpa(float gpa) {
        this.gpa = gpa;
    }

    public float getTargetGpa() {
        return targetGpa;
    }

    public void setTargetGpa(float targetGpa) {
        this.targetGpa = targetGpa;
    }

    public Users getUser() {
        return user;
    }

    public void setUser(Users user) {
        this.user = user;
    }



}
