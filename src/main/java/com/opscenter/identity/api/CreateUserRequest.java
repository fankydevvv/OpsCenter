package com.opscenter.identity.api;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.opscenter.identity.application.CreateUserCommand;

/**
 * Body of {@code POST /api/v1/users} (blueprint §7.2). Validation lives on the HTTP boundary;
 * business rules (uniqueness, unknown roles) live in {@code UserService}.
 */
public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 100)
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "may contain letters, digits, '.', '_' and '-' only")
        String username,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 255) String displayName,
        @NotBlank @Size(min = 8, max = 255, message = "must be between 8 and 255 characters") String password,
        @NotNull List<@NotBlank String> roleCodes) {

    public CreateUserCommand toCommand() {
        return new CreateUserCommand(username, email, displayName, password, roleCodes);
    }

    @Override
    public String toString() {
        return "CreateUserRequest[username=" + username + ", email=" + email + ", roleCodes=" + roleCodes + "]";
    }
}
