package org.tracker.gpatracker.security.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tracker.gpatracker.security.model.UserPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.UserRepo;

@Service
public class MyUserDetailService implements UserDetailsService {

    private static final Logger logger = LoggerFactory.getLogger(MyUserDetailService.class);
    private final UserRepo repo;

    public MyUserDetailService(UserRepo repo) {
        this.repo = repo;
    }

    //Load user by username when spring needs to authenticate a user
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Users user = repo.findByEmail(email);

        if (user == null || email.isEmpty()) {
            logger.warn("loadUserByUsername — user not found for email domain: {}", email.contains("@") ? email.substring(email.indexOf('@')) : "unknown");
            throw new UsernameNotFoundException("Username is empty");
        }

        return new UserPrincipal(user); //return a user detail object

    }
}
