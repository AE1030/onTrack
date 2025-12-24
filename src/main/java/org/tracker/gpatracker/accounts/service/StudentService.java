package org.tracker.gpatracker.accounts.service;

import org.springframework.stereotype.Service;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.model.Course;
import org.tracker.gpatracker.security.model.Users;

import java.util.Optional;

@Service
public class StudentService {

    public void createStudentAccount(Users user) {
        Student student = new Student();
        student.setUser(user);
    }

    public void addCurrentCourses(Optional<Course> course) {
        // Implementation to add current courses to the student



    }




}
