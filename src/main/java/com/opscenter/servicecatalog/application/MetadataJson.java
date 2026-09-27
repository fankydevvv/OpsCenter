package com.opscenter.servicecatalog.application;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.opscenter.audit.domain.SensitiveDataGuard;
import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.domain.InvalidRequestException;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts the free-form {@code metadata} object of services and environments between the API
 * ({@code Map}/{@link JsonNode}) and the JSONB column (a JSON string, base convention D-12), and
 * enforces two input rules on the way in:
 * <ul>
 *   <li>at most {@value #MAX_BYTES} bytes - metadata describes a service, it is not a document store;</li>
 *   <li>no credential-like keys ({@code password}, {@code token}, {@code apiKey} ...). The same guard
 *       protects {@code audit_logs} (TC-AUD-004); checking here turns a would-be 500 at audit time
 *       into a clear {@code 400} and keeps secrets out of the catalog (03-DB §30).</li>
 * </ul>
 */
@Component
public class MetadataJson {

    public static final int MAX_BYTES = 8 * 1024;

    private final JsonMapper jsonMapper;

    public MetadataJson(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * @param field name reported in the 400 message
     * @return JSON text, or {@code null} for a {@code null}/empty map
     */
    public String toJson(String field, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        JsonNode tree = jsonMapper.valueToTree(metadata);
        try {
            SensitiveDataGuard.assertNoSensitiveData(tree, field);
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidRequestException(ErrorCodes.VALIDATION_FAILED,
                    field + " must not contain credential-like keys (password, token, secret ...)");
        }
        String json = jsonMapper.writeValueAsString(tree);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new InvalidRequestException(ErrorCodes.VALIDATION_FAILED,
                    field + " must be at most " + MAX_BYTES + " bytes of JSON");
        }
        return json;
    }

    /** Stored JSON text back to a tree for DTOs; {@code null} stays {@code null}. */
    public JsonNode toNode(String json) {
        return json == null ? null : jsonMapper.readTree(json);
    }
}
