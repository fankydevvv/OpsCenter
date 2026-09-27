package com.opscenter.identity.infrastructure.security;

import java.time.Instant;

/**
 * A freshly signed access token plus the two values the login response needs
 * ({@code expiresIn} in seconds per 04-API §3.1).
 */
public record AccessToken(String value, long expiresInSeconds, Instant expiresAt) {
}
