package org.tracker.gpatracker.model;

import jakarta.persistence.*;

import java.util.HashSet;
import java.util.Set;

@Entity
public class Users {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String password;
    private String email;

    //Relationship between users and roles, many users can have the same role
    @ManyToOne
    @JoinColumn(name = "role_id")//best practice is to relate two datatables with their role name
    private UserRoles userRoles;

    //Relationship between users and student, the student is the owning side
    @OneToOne(mappedBy = "user") //This is not the owning side
    private Student student;

    public Users(Long id, String username, String password, String email) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.email = email;
    }

    //required no args constructor by the entity annotation
    public Users() {

    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    /*this method makes it so that the userRole reference object can be accessed directly from the user object
    connecting the two classes together which is useful for applying a role function to a user object as we do
    in the getauthorities method in the UserPrinciple class
     */

    public UserRoles getuserRoles() { //what does this function here do and why is it needed?
        return userRoles;
    }


    public void setUserroles(UserRoles userroles) {
        this.userRoles = userroles ;
    }

    public Student getStudent() {
        return student;
    }
    public void setStudent(Student student) {
        this.student = student;
    }
}


