package com.opscenter.shared.api;

import java.time.Instant;
import java.util.List;

import com.opscenter.shared.infrastructure.json.JsonConfig;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-01: Jackson 3 must render the error record exactly as 04-API §2.3 shows it - ISO-8601 UTC
 * timestamp, all six properties present, empty {@code fieldErrors} as {@code []}.
 */
class ApiErrorJsonTest {

    private final JsonMapper mapper = build();

    private static JsonMapper build() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JsonConfig().opscenterJsonMapperCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    void serialisesExactContractShape() {
        ApiError error = new ApiError(Instant.parse("2026-09-26T04:00:00Z"), "req-1", 400,
                "VALIDATION_FAILED", "Request validation failed",
                List.of(new FieldError("email", "must be a well-formed email address")));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(error));

        assertThat(json.properties().stream().map(e -> e.getKey()).toList())
                .containsExactly("timestamp", "requestId", "status", "code", "message", "fieldErrors");
        assertThat(json.get("timestamp").asString()).isEqualTo("2026-09-26T04:00:00Z");
        assertThat(json.get("status").asInt()).isEqualTo(400);
        assertThat(json.get("fieldErrors").get(0).get("field").asString()).isEqualTo("email");
    }

    @Test
    void nullFieldErrorsBecomeEmptyArray() {
        ApiError error = new ApiError(Instant.EPOCH, null, 500, "INTERNAL_ERROR", "boom", null);

        String json = mapper.writeValueAsString(error);

        assertThat(json).contains("\"fieldErrors\":[]").contains("\"requestId\":null");
    }
}
