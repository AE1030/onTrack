package org.tracker.gpatracker.security.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tracker.gpatracker.security.model.SecureToken;
import org.tracker.gpatracker.security.model.TokenType;
import org.tracker.gpatracker.security.model.Users;

@Repository
public interface SecureTokenRepository extends JpaRepository<SecureToken, Long> {
    SecureToken findByToken(String token);
    Long removeByToken(String token);
    void deleteByUser(Users user);
    void deleteByUserAndTokenType(Users user, TokenType tokenType);
    SecureToken findByUserAndTokenType(Users user, TokenType tokenType);
}
