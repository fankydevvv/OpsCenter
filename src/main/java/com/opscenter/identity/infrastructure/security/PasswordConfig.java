package com.opscenter.identity.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing (D-04, 01-SRS §18, 03-DB §30).
 * <p>
 * {@code DelegatingPasswordEncoder} stores the algorithm id in the hash itself
 * ({@code {bcrypt}$2a$10$...}). Today everything is BCrypt (cost 10); if the default is ever raised
 * or replaced, old hashes still verify and can be re-hashed on the next login - no big-bang
 * migration. Contributed from the identity module: the shared {@code SecurityConfig} never needs
 * to know how passwords are stored (D-16).
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
