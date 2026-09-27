package com.opscenter.identity.infrastructure.bootstrap;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * First administrator of an environment without seeded accounts
 * ({@code opscenter.security.bootstrap-admin.*}, D-27).
 * <p>
 * The password is read from {@code OPSCENTER_BOOTSTRAP_ADMIN_PASSWORD} and is <em>optional</em>:
 * when it is blank and no ADMIN exists, {@link AdminAccountBootstrap} logs a warning instead of
 * failing, because a fresh {@code local}/{@code test} database already has the seeded DEV
 * accounts and a running DEMO database already has its administrator.
 *
 * @param username    login of the account to create (lower-cased), default {@code admin}
 * @param email       e-mail of the account
 * @param displayName display name of the account
 * @param password    raw password; hashed with the same {@code PasswordHasher} as every user
 */
@Validated
@ConfigurationProperties(prefix = "opscenter.security.bootstrap-admin")
public record BootstrapAdminProperties(
        @NotBlank @DefaultValue("admin") String username,
        @NotBlank @Email @DefaultValue("admin@opscenter.local") String email,
        @NotBlank @DefaultValue("Administrator") String displayName,
        String password) {

    /** Same floor as {@code CreateUserRequest.password} so the bootstrap cannot bypass the policy. */
    public static final int MIN_PASSWORD_LENGTH = 8;
}
