package com.opscenter.identity.application;

import java.util.List;

/**
 * Input of {@code UserService.create}.
 * <p>
 * {@link #fingerprint()} is what gets hashed for the {@code Idempotency-Key} check (D-13): the raw
 * password is left out on purpose, so the stored {@code request_hash} can never be used to guess
 * a password offline, while username/email/roles still detect "same key, different request".
 */
public record CreateUserCommand(String username, String email, String displayName, String password,
                                List<String> roleCodes) {

    public CreateUserCommand {
        roleCodes = roleCodes == null ? List.of() : List.copyOf(roleCodes);
    }

    /** Password-free view used to compute the idempotency request hash. */
    public Fingerprint fingerprint() {
        return new Fingerprint(username, email, displayName, roleCodes);
    }

    public record Fingerprint(String username, String email, String displayName, List<String> roleCodes) {
    }

    @Override
    public String toString() {
        return "CreateUserCommand[username=" + username + ", email=" + email + ", roleCodes=" + roleCodes + "]";
    }
}
