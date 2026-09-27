package com.opscenter.identity.infrastructure.security;

import com.opscenter.identity.domain.PasswordHasher;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * {@link PasswordHasher} adapter over Spring Security's {@link PasswordEncoder} (BCrypt through the
 * delegating encoder, see {@link PasswordConfig}). Exists so the domain and application layers
 * depend on the small port instead of on Spring Security.
 */
@Component
public class BcryptPasswordHasher implements PasswordHasher {

    private final PasswordEncoder encoder;

    public BcryptPasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String storedHash) {
        return encoder.matches(rawPassword, storedHash);
    }
}
