package org.tracker.gpatracker.service;

import org.tracker.gpatracker.model.UserPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.model.Users;
import org.tracker.gpatracker.repository.UserRepo;

@Service
public class MyUserDetailService implements UserDetailsService {

    @Autowired
    private UserRepo repo;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Users user = repo.findByUsername(username);

        if (user == null || username.isEmpty()) {
            throw new UsernameNotFoundException("Username is empty");
        }

        return new UserPrincipal(user); //return a user detail object

        /*something interesting is happening
        when I login in with my actual credentials found on the database I am getting a bad credentials
        message in the frontend of the application but when I login in with a random username and password
        I am getting an error in the console?
         */

        /*Update: I have made a change to the if statement above which before I was doing this:
        if (user == null || username.isEmpty()) {
            throw new UsernameNotFoundException("Username is empty");
         and now I am getting invalid credentials for everything but no errors in my console.*/


    }
}
