package com.opscenter.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/auth/login} (04-API §3.1): {@code login} is a username or email. */
public record LoginRequest(
        @NotBlank @Size(max = 255) String login,
        @NotBlank @Size(max = 255) String password) {

    @Override
    public String toString() {
        return "LoginRequest[login=" + login + ", password=***]";
    }
}
