package org.tracker.gpatracker.model;

import jakarta.persistence.*;

import java.util.HashSet;
import java.util.Set;

@Entity
public class UserRoles {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long role_id;
    private String roleName;

    //Relationship between users and roles, one role can be shared by many users
    @OneToMany(mappedBy = "userRoles")//This is not the owning side
    private Set<Users> userSet = new HashSet<>();//Enables the bidirectional relationship and This guarantees that the users field always refers to a valid, albeit possibly empty, Set. This is a defensive programming practice that makes your objects safer and easier to work with.
    public Set<Users> getUserSet() {
        return userSet;
    }
    public UserRoles(Long id, String roleName) {
        this.role_id = id;
        this.roleName = roleName;
    }

    public UserRoles() {


    }

    public Long getId() {
        return role_id;
    }

    public void setId(Long id) {
        this.role_id = id;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

}



