package com.opscenter.shared.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.DomainException;
import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.infrastructure.persistence.IdempotencyKeyEntity;
import com.opscenter.shared.infrastructure.persistence.IdempotencyKeyStore;
import com.opscenter.shared.infrastructure.persistence.IdempotencyStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * Makes a "create" endpoint safe to retry (04-API §2.5, 03-DB §22, D-13).
 * <p>
 * Protocol for an optional {@code Idempotency-Key} header:
 * <ol>
 *   <li>Claim the key in its <b>own</b> short transaction ({@code IN_PROGRESS}). The unique
 *       constraint is the lock: a concurrent duplicate hits {@link DataIntegrityViolationException}.
 *       This happens <em>before</em> any business transaction exists, so the request never holds
 *       two pooled connections at once (03-DB §27 transaction boundaries).</li>
 *   <li>If the key already exists: same request hash and {@code COMPLETED} -> reload and answer
 *       {@code 200}; different hash -> {@code 422 IDEMPOTENCY_KEY_REUSED}; still
 *       {@code IN_PROGRESS} -> {@code 409 IDEMPOTENCY_IN_PROGRESS}; {@code FAILED} or past its
 *       {@code expires_at} -> the key is reclaimed and the operation runs again.</li>
 *   <li>Run the business operation in a transaction <b>opened here</b> and mark the key
 *       {@code COMPLETED} in that same transaction, so key and resource commit together.
 *       If that transaction rolls back, an after-completion hook marks the key {@code FAILED}
 *       (with the HTTP status the client saw) so the client can retry instead of being stuck.</li>
 * </ol>
 * Internal API calls use {@code integration_id = NULL} and the key is namespaced by the calling
 * user ({@code <actorId>:<key>}), so one user can neither probe nor replay another user's keys.
 * The Alertmanager webhook of Sprint 2 will pass its integration id instead.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyKeyStore store;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactions;
    private final CurrentUser currentUser;
    private final Clock clock;

    public IdempotencyService(IdempotencyKeyStore store, JsonMapper jsonMapper, TransactionTemplate transactions,
                              CurrentUser currentUser, Clock clock) {
        this.store = store;
        this.jsonMapper = jsonMapper;
        this.transactions = transactions;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** Shortcut for internal endpoints ({@code integration_id = NULL}, key scoped to the caller). */
    public <T> IdempotentResult<T> execute(String idempotencyKey, String requestHash, String resourceType,
                                           IdempotentOperation<T> operation) {
        String scopedKey = idempotencyKey == null || idempotencyKey.isBlank() ? idempotencyKey
                : currentUser.actorId().map(actor -> actor + ":" + idempotencyKey).orElse(idempotencyKey);
        return execute(null, scopedKey, requestHash, resourceType, operation);
    }

    /**
     * Executes {@code operation} at most once per {@code (integrationId, idempotencyKey)}.
     * A blank key disables the protection and simply runs the operation in a transaction.
     */
    public <T> IdempotentResult<T> execute(UUID integrationId, String idempotencyKey, String requestHash,
                                           String resourceType, IdempotentOperation<T> operation) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return transactions.execute(status -> IdempotentResult.created(operation.create()));
        }

        IdempotencyKeyEntity claim = claim(integrationId, idempotencyKey, requestHash, resourceType);
        if (claim == null) {
            IdempotencyKeyEntity existing = store.find(integrationId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key vanished after duplicate insert"));
            return handleExisting(existing, requestHash, resourceType, operation);
        }
        return runAndComplete(claim.getId(), operation);
    }

    /** Canonical SHA-256 (hex) of a request body, used as {@code request_hash}. */
    public String hashOf(Object request) {
        try {
            byte[] canonical = jsonMapper.writeValueAsBytes(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private IdempotencyKeyEntity claim(UUID integrationId, String key, String requestHash, String resourceType) {
        try {
            return store.insertInProgress(integrationId, key, requestHash, resourceType);
        }
        catch (DataIntegrityViolationException duplicate) {
            log.debug("Idempotency key {} already claimed", key);
            return null;
        }
    }

    private <T> IdempotentResult<T> handleExisting(IdempotencyKeyEntity existing, String requestHash,
                                                   String resourceType, IdempotentOperation<T> operation) {
        if (existing.getStatus() == IdempotencyStatus.FAILED || isExpired(existing)) {
            // A failed or expired key is free again: the new attempt takes it over with its own hash.
            store.reclaim(existing.getId(), requestHash, resourceType);
            return runAndComplete(existing.getId(), operation);
        }
        if (!sameHash(existing.getRequestHash(), requestHash)) {
            throw new BusinessRuleException(ErrorCodes.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used with a different request body");
        }
        if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            throw new ConflictException(ErrorCodes.IDEMPOTENCY_IN_PROGRESS,
                    "A request with the same Idempotency-Key is still being processed");
        }
        UUID resourceId = existing.getResourceId();
        if (resourceId == null) {
            throw new IllegalStateException("Completed idempotency key without resource id: " + existing.getId());
        }
        return transactions.execute(status -> IdempotentResult.replayed(operation.reload(resourceId)));
    }

    private boolean isExpired(IdempotencyKeyEntity key) {
        Instant expiresAt = key.getExpiresAt();
        return expiresAt != null && !expiresAt.isAfter(clock.instant());
    }

    /**
     * The business operation and the {@code COMPLETED} update share one transaction. A failure
     * inside it rolls that transaction back, and the hook registered first turns the key into
     * {@code FAILED} in a fresh transaction (with the status code the client is about to receive).
     */
    private <T> IdempotentResult<T> runAndComplete(UUID claimId, IdempotentOperation<T> operation) {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        return transactions.execute(status -> {
            registerRollbackHook(claimId, failure);
            T created;
            try {
                created = operation.create();
            }
            catch (RuntimeException ex) {
                failure.set(ex);
                throw ex;
            }
            store.markCompleted(claimId, operation.resourceId(created), 201);
            return IdempotentResult.created(created);
        });
    }

    /**
     * If the surrounding business transaction rolls back after {@code create()} returned, the
     * {@code COMPLETED} update is lost with it and the key would stay {@code IN_PROGRESS} forever.
     * The hook runs after completion and flips it to {@code FAILED} in a fresh transaction.
     */
    private void registerRollbackHook(UUID claimId, AtomicReference<RuntimeException> failure) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    RuntimeException ex = failure.get();
                    int responseCode = ex instanceof DomainException domain ? domain.status().value() : 500;
                    String reason = ex == null ? "Business transaction rolled back"
                            : ex.getClass().getSimpleName() + ": " + ex.getMessage();
                    store.markFailed(claimId, responseCode, reason);
                }
            }
        });
    }

    private static boolean sameHash(String stored, String incoming) {
        return Optional.ofNullable(stored).orElse("").equals(Optional.ofNullable(incoming).orElse(""));
    }
}
