package com.opscenter.servicecatalog.domain;

import com.opscenter.shared.domain.InvalidRequestException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-33: services.code is the lower-case join key with the Prometheus label {@code service}. */
class ServiceCodeTest {

    @Test
    void require_trimsAndLowerCases() {
        assertThat(ServiceCode.require("  Odoo-ERP ")).isEqualTo("odoo-erp");
        assertThat(ServiceCode.require("payment.api_v2")).isEqualTo("payment.api_v2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-starts-with-dash", "has space", "emoji☃", "slash/code"})
    void require_rejectsCodesThatCannotBeALabelValue(String raw) {
        assertThatThrownBy(() -> ServiceCode.require(raw))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Service code must match");
    }

    @Test
    void isValid_limitsLengthTo100() {
        assertThat(ServiceCode.isValid("a".repeat(100))).isTrue();
        assertThat(ServiceCode.isValid("a".repeat(101))).isFalse();
        assertThat(ServiceCode.isValid(null)).isFalse();
    }
}
