package com.opscenter.identity.domain;

/**
 * Port through which the domain deals with passwords without knowing the algorithm (D-04).
 * <p>
 * The implementation ({@code BcryptPasswordHasher}) wraps Spring Security's
 * {@code DelegatingPasswordEncoder}; the domain and the unit tests only see this interface, so
 * switching to Argon2 later is an infrastructure change and tests can use a cheap stub.
 */
public interface PasswordHasher {

    /** Produces a salted hash suitable for {@code users.password_hash} (never the raw value). */
    String hash(String rawPassword);

    /** Constant-time comparison of a raw password with a stored hash. */
    boolean matches(String rawPassword, String storedHash);
}
