package org.tracker.gpatracker.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tracker.gpatracker.model.UserRoles;

public interface RolesRepo extends JpaRepository<UserRoles, Integer> {
    UserRoles findById(Long role_id);
}
