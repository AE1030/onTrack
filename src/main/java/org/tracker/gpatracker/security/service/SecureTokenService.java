package org.tracker.gpatracker.security.service;

import org.tracker.gpatracker.security.model.SecureToken;
import org.tracker.gpatracker.security.model.Users;

public interface SecureTokenService {
    SecureToken saveAndFlush(Users user);
    SecureToken findByToken(String token);
    void removeToken(String token);
}
