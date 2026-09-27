package com.opscenter.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.opscenter.identity.application.UpdateUserCommand;

/**
 * Body of {@code PATCH /api/v1/users/{id}}: partial update, omitted fields stay unchanged.
 * {@code version} is mandatory - it is the optimistic-lock token from the last read (03-DB §28).
 */
public record UpdateUserRequest(
        @Size(min = 1, max = 255) String displayName,
        @Email @Size(max = 255) String email,
        @NotNull Long version) {

    public UpdateUserCommand toCommand() {
        return new UpdateUserCommand(displayName, email, version);
    }
}
