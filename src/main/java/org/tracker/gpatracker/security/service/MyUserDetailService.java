package org.tracker.gpatracker.security.service;

import org.tracker.gpatracker.security.model.UserPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.UserRepo;

@Service
public class MyUserDetailService implements UserDetailsService {

    @Autowired
    private UserRepo repo;

    //Load user by username when spring needs to authenticate a user
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Users user = repo.findByEmail(email);

        if (user == null || email.isEmpty()) {
            throw new UsernameNotFoundException("Username is empty");
        }

        return new UserPrincipal(user); //return a user detail object

    }
}
