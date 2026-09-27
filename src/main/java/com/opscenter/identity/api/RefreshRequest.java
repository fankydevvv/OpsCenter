package com.opscenter.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/refresh}: the opaque refresh token received at login.
 * A real token is 43 characters (32 random bytes, base64url); the size cap stops a caller from
 * making the public endpoint hash arbitrarily large bodies.
 */
public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) {

    @Override
    public String toString() {
        return "RefreshRequest[refreshToken=***]";
    }
}
