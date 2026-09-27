package com.opscenter.identity.application;

/**
 * Input of {@code UserService.update} ({@code PATCH /users/{id}}). {@code null} fields mean
 * "leave unchanged"; {@code version} must match the current row (03-DB §28).
 */
public record UpdateUserCommand(String displayName, String email, long version) {
}
