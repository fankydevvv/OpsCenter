package com.opscenter.identity.api;

import jakarta.validation.constraints.Size;

/** Optional body of {@code POST /api/v1/users/{id}/lock}; the reason ends up in the outbox event. */
public record LockUserRequest(@Size(max = 500) String reason) {
}
