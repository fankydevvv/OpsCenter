package com.opscenter.servicecatalog.domain;

import com.opscenter.shared.domain.InvalidRequestException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-33: one canonical spelling per environment, whatever the label or the client wrote. */
class EnvironmentCodeTest {

    @ParameterizedTest
    @CsvSource({
            "prod, PRODUCTION",
            "PRD, PRODUCTION",
            "Production, PRODUCTION",
            "stg, STAGING",
            "stage, STAGING",
            "development, DEV",
            "dev, DEV",
            "' DEV ', DEV",
            "pre-prod, PRE_PROD",
            "uat 2, UAT_2"
    })
    void normalize_mapsAliasesToTheCanonicalCode(String raw, String expected) {
        assertThat(EnvironmentCode.normalize(raw)).isEqualTo(expected);
        assertThat(EnvironmentCode.require(raw)).isEqualTo(expected);
    }

    @Test
    void blankIsNoEnvironment() {
        assertThat(EnvironmentCode.normalize(null)).isNull();
        assertThat(EnvironmentCode.normalize("  ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"D", "1DEV", "DEV!", "_DEV"})
    void require_rejectsCodesOutsideTheDatabaseRegex(String raw) {
        assertThatThrownBy(() -> EnvironmentCode.require(raw))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Environment code");
    }
}
