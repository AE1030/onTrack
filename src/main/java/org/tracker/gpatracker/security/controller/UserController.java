package org.tracker.gpatracker.security.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.tracker.gpatracker.annotation.TrackExecution;
import org.tracker.gpatracker.security.dto.LoginRequest;
import org.tracker.gpatracker.security.dto.RegisterRequest;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.service.UserService;

@RestController
public class UserController {

    @Autowired
    private UserService service;

    @PostMapping("/register")
    @TrackExecution
    public ResponseEntity<Users> register(@RequestBody RegisterRequest registerDTO) {
        Users user = new Users();
        user.setEmail(registerDTO.getEmail());
        user.setUsername(registerDTO.getUsername());
        user.setPassword(registerDTO.getPassword());
        Users savedUser = service.register(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedUser);
    }
    @PostMapping("/login")
    public ResponseEntity<String>login(@RequestBody LoginRequest loginDTO) {
        Users user = new Users();
        user.setEmail(loginDTO.getEmail());
        user.setPassword(loginDTO.getPassword());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.verifyLogin(user));
    }
}
