package org.tracker.gpatracker.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.model.UserRoles;
import org.tracker.gpatracker.model.Users;
import org.tracker.gpatracker.repository.RolesRepo;
import org.tracker.gpatracker.repository.UserRepo;

@Service
public class UserService {

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private RolesRepo rolesRepo;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    AuthenticationManager authManager;

    @Autowired
    JWTService jwtService;
    public Users register(Users user) {
        Long defaultRoleId = 3L;

        UserRoles defaultRole = rolesRepo.findById(defaultRoleId); // Fetch the default role from the database with the role id
        user.setUserroles(defaultRole); // Assign the default role to the user

        user.setPassword(passwordEncoder.encode(user.getPassword()));


        /*


        Something is wrong with this validation logic


        //Logic to check if a user already exists
        if (userRepo.existsByEmail(user.getEmail())){
            throw new IllegalArgumentException("Email already in use");
        }

        //Logic to make sure user goes to mcmaster
        if (user.getEmail().endsWith("@mcmaster.ca")){
            throw new IllegalArgumentException("Please use a valid McMaster email");
        }

        //logic to verify email
        //maybe let jwt handle that
        */


        return userRepo.save(user);


    }

    public String verifyLogin(Users user) {
        //If the user is not authenticated the authManager.authenticate returns an unchecked error
        //unchecked errors don't need to be handled
        //but we neeed to catch and handle the error if we want to output login failed to the user
        try {
            Authentication auth =
                    authManager.authenticate(
                            new UsernamePasswordAuthenticationToken(
                                    user.getUsername(),
                                    user.getPassword()
                            )
                    );
            return jwtService.generateToken(user.getUsername());
        } catch (AuthenticationException e) {
            return "Login Failed";

        }


    }
}
