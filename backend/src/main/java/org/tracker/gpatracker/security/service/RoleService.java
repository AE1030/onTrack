package org.tracker.gpatracker.security.service;

import org.springframework.stereotype.Service;
import org.tracker.gpatracker.security.model.Role;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.RolesRepo;
import org.tracker.gpatracker.security.repository.UserRepo;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class RoleService {

    private final RolesRepo rolesRepo;
    private final UserRepo userRepo;

    public RoleService(RolesRepo rolesRepo, UserRepo userRepo) {
        this.rolesRepo = rolesRepo;
        this.userRepo = userRepo;
    }

    public List<Role> findAll(){
        return rolesRepo.findAll();
    }

    public Optional<Role> findById(Long id){
        return rolesRepo.findById(id);
    }

    public void delete(Long id){
        rolesRepo.deleteById(id);
    }

    public Role save(Role role){
        return rolesRepo.save(role);
    }
    public void assignUserRole(Long userId, Long roleId){
        Users user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        Role role = rolesRepo.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
        Set<Role> userRoles = user.getRoles();
        userRoles.add(role);
        user.setRoles(userRoles);
        userRepo.save(user);
    }

    public void unassignUserRole(Long userId, Long roleId){
        Users user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        Set<Role> userRoles = user.getRoles();
        userRoles.removeIf(x -> x.getId().equals(roleId));
        user.setRoles(userRoles);
        userRepo.save(user);
    }

    public Set<Role> getUserRoles(Users user){
        return user.getRoles();
    }

    public Set<Role> getUserNotRoles(Users user){
        return rolesRepo.getUserNotRoles(user.getId());

    }
}
