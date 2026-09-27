package com.opscenter.shared.application;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.infrastructure.persistence.IdempotencyKeyEntity;
import com.opscenter.shared.infrastructure.persistence.IdempotencyKeyRepository;
import com.opscenter.shared.infrastructure.persistence.IdempotencyKeyStore;
import com.opscenter.shared.infrastructure.persistence.IdempotencyStatus;
import com.opscenter.support.AbstractIntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TC-IDEMP-001 (adapted to the shared kernel, D-13): the same Idempotency-Key must not create a
 * resource twice, a reused key with another body is rejected, a failed attempt can be retried,
 * an expired key is reclaimed and the retention purge removes what is past its TTL.
 * The service owns the business transaction, so most calls here are made without an outer one.
 */
class IdempotencyServiceIT extends AbstractIntegrationTest {

    @Autowired
    IdempotencyService service;

    @Autowired
    IdempotencyKeyStore store;

    @Autowired
    IdempotencyKeyRepository repository;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Clock clock;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    /** A fake "create team" use case that remembers what it created. */
    static class FakeCreate implements IdempotentOperation<String> {
        final AtomicInteger calls = new AtomicInteger();
        final Map<UUID, String> storage = new HashMap<>();
        RuntimeException failWith;

        @Override
        public String create() {
            calls.incrementAndGet();
            if (failWith != null) {
                throw failWith;
            }
            UUID id = UUID.randomUUID();
            String resource = "team-" + id;
            storage.put(id, resource);
            return resource;
        }

        @Override
        public UUID resourceId(String created) {
            return UUID.fromString(created.substring("team-".length()));
        }

        @Override
        public String reload(UUID resourceId) {
            return storage.get(resourceId);
        }
    }

    @Test
    void TC_IDEMP_001_sameKeyAndBody_createsOnceAndReplays() {
        FakeCreate op = new FakeCreate();
        String hash = service.hashOf(Map.of("code", "PAYMENT"));

        IdempotentResult<String> first = service.execute("key-1", hash, "Team", op);
        IdempotentResult<String> second = service.execute("key-1", hash, "Team", op);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.value()).isEqualTo(first.value());
        assertThat(op.calls.get()).isEqualTo(1);

        IdempotencyKeyEntity row = repository.findByIntegrationIdIsNullAndIdempotencyKey("key-1").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(row.getResourceId()).isEqualTo(op.resourceId(first.value()));
        assertThat(row.getResourceType()).isEqualTo("Team");
        assertThat(row.getResponseCode()).isEqualTo(201);
        assertThat(row.getExpiresAt()).isAfter(row.getCreatedAt());
    }

    @Test
    void sameKeyDifferentBody_isRejectedWith422Code() {
        FakeCreate op = new FakeCreate();
        service.execute("key-2", service.hashOf(Map.of("code", "A")), "Team", op);

        assertThatThrownBy(() -> service.execute("key-2", service.hashOf(Map.of("code", "B")), "Team", op))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(op.calls.get()).isEqualTo(1);
    }

    @Test
    void blankKey_disablesProtection() {
        FakeCreate op = new FakeCreate();

        service.execute(null, "h", "Team", op);
        service.execute("  ", "h", "Team", op);

        assertThat(op.calls.get()).isEqualTo(2);
        assertThat(repository.count()).isZero();
    }

    @Test
    void failingOperation_marksKeyFailedWithTheClientStatus_andAllowsRetry() {
        FakeCreate op = new FakeCreate();
        op.failWith = new BusinessRuleException("TEAM_RULE_BROKEN", "not today");
        String hash = service.hashOf(Map.of("code", "C"));

        assertThatThrownBy(() -> service.execute("key-3", hash, "Team", op))
                .isInstanceOf(BusinessRuleException.class);
        IdempotencyKeyEntity failed = repository.findByIntegrationIdIsNullAndIdempotencyKey("key-3").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(failed.getResponseCode()).as("03-DB §22: the status the client received").isEqualTo(422);

        op.failWith = null;
        IdempotentResult<String> retry = service.execute("key-3", hash, "Team", op);

        assertThat(retry.replayed()).isFalse();
        assertThat(op.calls.get()).isEqualTo(2);
        assertThat(repository.findByIntegrationIdIsNullAndIdempotencyKey("key-3").orElseThrow().getStatus())
                .isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    void reclaimingAFailedKey_isAtomic_onlyOneOfTwoRetriesWins() {
        // Review finding: two identical retries that both saw the key FAILED both restarted it and
        // both ran the operation. The takeover is now a conditional UPDATE.
        FakeCreate op = new FakeCreate();
        op.failWith = new BusinessRuleException("TEAM_RULE_BROKEN", "not today");
        String hash = service.hashOf(Map.of("code", "R"));
        assertThatThrownBy(() -> service.execute("key-race", hash, "Team", op)).isInstanceOf(BusinessRuleException.class);
        UUID keyId = repository.findByIntegrationIdIsNullAndIdempotencyKey("key-race").orElseThrow().getId();

        boolean first = store.reclaim(keyId, hash, "Team");
        boolean second = store.reclaim(keyId, hash, "Team");

        assertThat(first).isTrue();
        assertThat(second).as("the key is IN_PROGRESS now - the second retry must not run the operation").isFalse();
        assertThat(repository.findById(keyId).orElseThrow().getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        // the loser is answered like any duplicate of a request in progress
        assertThatThrownBy(() -> service.execute("key-race", hash, "Team", new FakeCreate()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void unexpectedFailure_isRecordedAs500() {
        FakeCreate op = new FakeCreate();
        op.failWith = new IllegalStateException("db hiccup");

        assertThatThrownBy(() -> service.execute("key-3b", service.hashOf(Map.of("code", "C")), "Team", op))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.findByIntegrationIdIsNullAndIdempotencyKey("key-3b").orElseThrow().getResponseCode())
                .isEqualTo(500);
    }

    @Test
    void businessTransactionRollingBackAfterCreate_marksKeyFailedInsteadOfStuckInProgress() {
        FakeCreate op = new FakeCreate();
        String hash = service.hashOf(Map.of("code", "D"));

        // a caller that already runs in a transaction joins it - and may still roll it back later
        assertThatThrownBy(() -> tx.execute(s -> {
            service.execute("key-4", hash, "Team", op);
            throw new IllegalStateException("later step of the same transaction fails");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repository.findByIntegrationIdIsNullAndIdempotencyKey("key-4").orElseThrow().getStatus())
                .isEqualTo(IdempotencyStatus.FAILED);
        IdempotentResult<String> retry = service.execute("key-4", hash, "Team", op);
        assertThat(retry.replayed()).isFalse();
    }

    @Test
    void keyStillInProgress_isReportedAsConflict() {
        String hash = service.hashOf(Map.of("code", "E"));
        store.insertInProgress(null, "key-5", hash, "Team");

        assertThatThrownBy(() -> service.execute("key-5", hash, "Team", new FakeCreate()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_IN_PROGRESS");
    }

    @Test
    void expiredCompletedKey_isReclaimed_andTheOperationRunsAgain() {
        FakeCreate op = new FakeCreate();
        String hash = service.hashOf(Map.of("code", "F"));
        IdempotencyKeyEntity stale = new IdempotencyKeyEntity(UUID.randomUUID(), null, "key-6", hash, "Team",
                clock.instant().minus(Duration.ofDays(2)), clock.instant().minus(Duration.ofDays(1)));
        stale.complete(UUID.randomUUID(), 201);
        repository.save(stale);

        IdempotentResult<String> result = service.execute("key-6", hash, "Team", op);

        assertThat(result.replayed()).as("past expires_at the old answer is no longer replayed").isFalse();
        assertThat(op.calls.get()).isEqualTo(1);
        IdempotencyKeyEntity row = repository.findByIntegrationIdIsNullAndIdempotencyKey("key-6").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(row.getExpiresAt()).isAfter(clock.instant());
        assertThat(row.getResourceId()).isEqualTo(op.resourceId(result.value()));
    }

    @Test
    void purgeExpired_deletesOnlyKeysPastTheirTtl() {
        IdempotencyKeyEntity expired = new IdempotencyKeyEntity(UUID.randomUUID(), null, "key-7", "h", "Team",
                clock.instant().minus(Duration.ofDays(2)), clock.instant().minus(Duration.ofHours(1)));
        expired.complete(UUID.randomUUID(), 201);
        repository.save(expired);
        store.insertInProgress(null, "key-8", "h", "Team");

        int removed = store.purgeExpired(clock.instant());

        assertThat(removed).isEqualTo(1);
        assertThat(repository.findByIntegrationIdIsNullAndIdempotencyKey("key-7")).isEmpty();
        assertThat(repository.findByIntegrationIdIsNullAndIdempotencyKey("key-8")).isPresent();
    }

    @Test
    void hashOf_isStableAndBodySensitive() {
        assertThat(service.hashOf(Map.of("a", 1))).isEqualTo(service.hashOf(Map.of("a", 1)))
                .hasSize(64)
                .isNotEqualTo(service.hashOf(Map.of("a", 2)));
    }
}
