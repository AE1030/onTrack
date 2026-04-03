package org.tracker.gpatracker.accounts.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.accounts.model.Student;

@Repository
public interface StudentRepo extends JpaRepository<Student, Long> {

    Student findByUserId(Long userId);

}
