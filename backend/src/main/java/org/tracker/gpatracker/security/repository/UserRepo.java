package org.tracker.gpatracker.security.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.security.model.Users;

@Repository
public interface UserRepo extends JpaRepository<Users, Long> {

    Users findByUsername(String username);
    Users findByEmail(String email);
    boolean existsByEmail(String email);

}
