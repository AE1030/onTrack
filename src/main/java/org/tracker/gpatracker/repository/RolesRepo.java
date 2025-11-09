package org.tracker.gpatracker.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.model.UserRoles;

@Repository
public interface RolesRepo extends JpaRepository<UserRoles, Integer> {
    UserRoles findById(Long role_id);
}
