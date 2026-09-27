package com.opscenter.integration.domain;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 03-DB §30 / R-28: credential-like label names never reach PostgreSQL with their value. */
class SensitiveLabelMaskerTest {

    @Test
    void credentialLikeNames_areMasked_otherValuesAndTheOrderAreKept() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("alertname", "DbDown");
        labels.put("db_password", "hunter2");
        labels.put("Api-Key", "k");
        labels.put("client_secret", "s");
        labels.put("session_cookie", "c");
        labels.put("instance", "db:5432");

        Map<String, String> masked = SensitiveLabelMasker.mask(labels);

        assertThat(masked).containsExactly(Map.entry("alertname", "DbDown"), Map.entry("db_password", "***"),
                Map.entry("Api-Key", "***"), Map.entry("client_secret", "***"), Map.entry("session_cookie", "***"),
                Map.entry("instance", "db:5432"));
        assertThat(labels).containsEntry("db_password", "hunter2");
    }

    @Test
    void labelsWithoutValue_areDropped_soImmutableCopiesDownstreamCannotFail() {
        // review finding: {"labels":{"alertname":"X","team":null}} made Map.copyOf throw -> 500 forever
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("alertname", "X");
        labels.put("team", null);
        labels.put("api_token", null);

        Map<String, String> masked = SensitiveLabelMasker.mask(labels);

        assertThat(masked).containsExactly(Map.entry("alertname", "X"));
        assertThat(Map.copyOf(masked)).hasSize(1);
    }

    @Test
    void nullInput_givesAnEmptyMap() {
        assertThat(SensitiveLabelMasker.mask(null)).isEmpty();
        assertThat(SensitiveLabelMasker.isSensitive(null)).isFalse();
        assertThat(SensitiveLabelMasker.isSensitive("tokenizer_version")).isTrue();
        assertThat(SensitiveLabelMasker.isSensitive("severity")).isFalse();
    }
}
