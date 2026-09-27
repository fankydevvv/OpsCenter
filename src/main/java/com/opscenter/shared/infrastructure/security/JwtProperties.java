package com.opscenter.shared.infrastructure.security;

import java.time.Duration;
import java.util.Locale;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * JWT settings ({@code opscenter.security.jwt.*}, D-01).
 * <p>
 * {@code @Validated} makes a missing, short or placeholder secret a start-up failure instead of a
 * runtime "signature invalid" surprise: HS256 needs at least 256 bits, i.e. 32 bytes, and a
 * secret that is committed to the repository (such as the {@code change-me...} text in
 * {@code env.example}) would let anyone forge an administrator token. The secret is never
 * defaulted here - profiles supply it from the environment (D-22).
 *
 * @param secret     HMAC key material (>= 32 characters, not a documented placeholder)
 * @param accessTtl  lifetime of an access token; 30 min = {@code expiresIn: 1800} in 04-API §3.1
 * @param refreshTtl lifetime of a refresh token and its session (D-02)
 * @param issuer     value of the {@code iss} claim, validated on every request
 */
@Validated
@ConfigurationProperties(prefix = "opscenter.security.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "opscenter.security.jwt.secret must be at least 32 characters") String secret,
        @NotNull @DefaultValue("PT30M") Duration accessTtl,
        @NotNull @DefaultValue("P7D") Duration refreshTtl,
        @NotBlank @DefaultValue("opscenter") String issuer) {

    /** Prefix of every placeholder ever shipped in {@code env.example} / documentation. */
    static final String PLACEHOLDER_PREFIX = "change-me";

    /**
     * Rejects the documented placeholder so "I copied env.example and it started" cannot happen
     * with a well-known signing key (05-DEPLOY §9 "no secret in source"). The DEV-ONLY default of
     * the {@code local} profile is intentionally not matched: it is confined to a developer machine
     * by profile, and the container profile {@code dev} has no default at all.
     */
    @AssertTrue(message = "opscenter.security.jwt.secret is the documented placeholder; generate a random value "
            + "(e.g. openssl rand -hex 32) and set JWT_SECRET / OPSCENTER_JWT_SECRET")
    public boolean isNotAPlaceholder() {
        return secret == null || !secret.trim().toLowerCase(Locale.ROOT).startsWith(PLACEHOLDER_PREFIX);
    }
}
