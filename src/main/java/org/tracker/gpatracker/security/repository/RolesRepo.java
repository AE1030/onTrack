package org.tracker.gpatracker.security.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.security.model.Role;

import java.util.Set;

@Repository
public interface RolesRepo extends JpaRepository<Role, Long> {

    @Query(
            value = "SELECT * FROM role WHERE id NOT IN (SELECT role_id FROM user_role WHERE user_id = ?1)",
            nativeQuery = true
            //?1 is the first parameter argument passed to the method (userId)
    )

    Set<Role> getUserNotRoles(Long userId);

}
