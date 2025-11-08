package org.tracker.gpatracker.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.model.Users;

@Repository
public interface UserRepo extends JpaRepository<Users, Integer> {

    Users findByUsername(String username);
    boolean existsByEmail(String email);

}
