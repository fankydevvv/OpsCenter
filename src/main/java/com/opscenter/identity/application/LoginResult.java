package com.opscenter.identity.application;

/**
 * Response of {@code POST /auth/login} and {@code POST /auth/refresh} (04-API §3.1):
 * a short-lived access JWT, a one-time refresh token and the profile the UI needs immediately.
 * {@code expiresIn} is in seconds (1800 for the 30 minute default).
 */
public record LoginResult(String accessToken, String refreshToken, long expiresIn, AuthenticatedUser user) {
}
