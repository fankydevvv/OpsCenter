package com.opscenter.shared.infrastructure.security;

import java.time.Duration;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 05-DEPLOY §9 / D-22: a copied placeholder or a short secret must fail validation - the same
 * validator Boot applies to {@code @ConfigurationProperties} at start-up.
 */
class JwtPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<ConstraintViolation<JwtProperties>> validate(String secret) {
        return validator.validate(new JwtProperties(secret, Duration.ofMinutes(30), Duration.ofDays(7), "opscenter"));
    }

    @Test
    void theDocumentedPlaceholderIsRejectedEvenThoughItIsLongEnough() {
        String placeholder = "change-me-to-a-random-string-of-at-least-32-characters";
        assertThat(placeholder.length()).isGreaterThanOrEqualTo(32);

        assertThat(validate(placeholder)).extracting(ConstraintViolation::getMessage)
                .anyMatch(message -> message.contains("placeholder"));
        assertThat(validate("CHANGE-ME-later-but-long-enough-0123456789")).isNotEmpty();
    }

    @Test
    void shortSecretsAreRejected_andARandomHexSecretIsAccepted() {
        assertThat(validate("too-short")).isNotEmpty();
        assertThat(validate("")).isNotEmpty();
        assertThat(validate("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")).isEmpty();
        // the DEV-ONLY default of the local profile stays valid (confined by profile, D-22)
        assertThat(validate("dev-only-secret-change-me-0123456789abcdef")).isEmpty();
    }
}
