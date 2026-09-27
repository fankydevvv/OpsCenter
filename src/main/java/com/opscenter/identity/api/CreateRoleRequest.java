package com.opscenter.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.opscenter.identity.application.CreateRoleCommand;

/** Body of {@code POST /api/v1/roles}. Codes are upper-case identifiers such as {@code AUDITOR}. */
public record CreateRoleRequest(
        @NotBlank @Size(min = 2, max = 50)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "must be upper-case letters, digits or '_' and start with a letter")
        String code,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 1000) String description) {

    public CreateRoleCommand toCommand() {
        return new CreateRoleCommand(code, name, description);
    }
}
