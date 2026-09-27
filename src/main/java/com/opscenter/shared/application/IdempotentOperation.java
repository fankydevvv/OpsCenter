package com.opscenter.shared.application;

import java.util.UUID;

/**
 * The three things {@link IdempotencyService} needs from a "create" use case:
 * how to perform it, how to identify the created resource, and how to load it again on a replay.
 *
 * @param <T> the result returned to the caller (usually a response DTO)
 */
public interface IdempotentOperation<T> {

    /** Executes the business operation for the first time (inside the caller's transaction). */
    T create();

    /** Id of the resource produced by {@link #create()}, stored in {@code idempotency_keys.resource_id}. */
    UUID resourceId(T created);

    /** Loads the previously created resource so a retried request receives the same answer. */
    T reload(UUID resourceId);
}
