package org.tracker.gpatracker.security.service;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.keygen.BytesKeyGenerator;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.security.model.SecureToken;
import org.tracker.gpatracker.security.model.TokenType;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.SecureTokenRepository;

import java.time.LocalDateTime;
import java.util.Base64;

@Service
public class DefaultSecureTokenService implements SecureTokenService {

    private static final BytesKeyGenerator DEFAULT_TOKEN_GENERATOR = KeyGenerators.secureRandom(32);

    @Value("${security.tokens.expiry-seconds}")
    private long tokenValidityInSeconds;

    private final SecureTokenRepository secureTokenRepository;

    public DefaultSecureTokenService(SecureTokenRepository secureTokenRepository) {
        this.secureTokenRepository = secureTokenRepository;
    }

    @Override
    @Transactional
    public SecureToken saveAndFlush(Users user) {
        return saveAndFlush(user, TokenType.VERIFICATION);
    }

    @Override
    @Transactional
    public SecureToken saveAndFlush(Users user, TokenType tokenType) {
        String tokenValue = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(DEFAULT_TOKEN_GENERATOR.generateKey());
        SecureToken secureToken = new SecureToken();
        secureToken.setToken(tokenValue);
        secureToken.setUser(user);
        secureToken.setTokenType(tokenType);
        secureToken.setExpiredAt(LocalDateTime.now().plusSeconds(tokenValidityInSeconds));
        secureTokenRepository.save(secureToken);
        return secureToken;
    }

    @Override
    public SecureToken findByToken(String token) {
        return secureTokenRepository.findByToken(token);
    }

    @Override
    public void removeToken(String token) {
        SecureToken existing = secureTokenRepository.findByToken(token);
        if (existing != null) {
            secureTokenRepository.delete(existing);
        }
    }
}
