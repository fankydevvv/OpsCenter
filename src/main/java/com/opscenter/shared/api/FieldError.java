package com.opscenter.shared.api;

/**
 * One invalid field inside {@link ApiError#fieldErrors()} (04-API §2.3).
 *
 * @param field   the request field (JSON property or query parameter name)
 * @param message why it was rejected
 */
public record FieldError(String field, String message) {
}
