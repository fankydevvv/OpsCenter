package com.opscenter.shared.application;

/**
 * Outcome of {@link IdempotencyService#execute}: the value plus whether it came from a replay.
 * Controllers use {@code replayed} to answer {@code 200 OK} instead of {@code 201 Created} (D-13).
 *
 * @param <T> result type
 */
public record IdempotentResult<T>(T value, boolean replayed) {

    public static <T> IdempotentResult<T> created(T value) {
        return new IdempotentResult<>(value, false);
    }

    public static <T> IdempotentResult<T> replayed(T value) {
        return new IdempotentResult<>(value, true);
    }
}
