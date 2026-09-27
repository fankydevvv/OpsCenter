package com.opscenter.integration.api;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import com.opscenter.integration.application.alertmanager.AlertmanagerWebhook;
import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.domain.InvalidRequestException;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses and validates the raw webhook bytes (blueprint §4.2 step 2).
 * <p>
 * The controller reads the body as bytes itself - instead of letting Spring bind {@code @RequestBody}
 * - because the exact bytes are needed twice more: archived verbatim in object storage (D-42) and
 * hashed as the idempotency key (D-43). Re-serialising the parsed object would not give the same
 * bytes. Errors follow the API contract: unparseable JSON -> 400 {@code REQUEST_MALFORMED}; bean
 * validation -> 400 {@code VALIDATION_FAILED} with {@code fieldErrors} such as
 * {@code alerts[0].labels.alertname} (the global handler renders {@link ConstraintViolationException}).
 */
@Component
public class AlertmanagerPayloadReader {

    private final JsonMapper jsonMapper;
    private final Validator validator;

    public AlertmanagerPayloadReader(JsonMapper jsonMapper, Validator validator) {
        this.jsonMapper = jsonMapper;
        this.validator = validator;
    }

    public AlertmanagerWebhook read(byte[] body) {
        AlertmanagerWebhook payload;
        try {
            payload = jsonMapper.readValue(body, AlertmanagerWebhook.class);
        }
        catch (JacksonException ex) {
            // The parser message may quote the input; it is not echoed back or logged.
            throw new InvalidRequestException(ErrorCodes.REQUEST_MALFORMED,
                    "Request body is not a valid Alertmanager webhook JSON document");
        }
        if (payload == null) {
            throw new InvalidRequestException(ErrorCodes.REQUEST_MALFORMED, "Request body is empty");
        }
        Set<ConstraintViolation<AlertmanagerWebhook>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return payload;
    }
}
