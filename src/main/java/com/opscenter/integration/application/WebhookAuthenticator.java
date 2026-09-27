package com.opscenter.integration.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.opscenter.integration.domain.IntegrationAuthType;
import com.opscenter.integration.domain.IntegrationSource;
import com.opscenter.integration.domain.IntegrationSourceType;
import com.opscenter.integration.infrastructure.IntegrationSourceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Decides whether a webhook request really comes from the registered source (01-SRS §18 "webhook
 * authentication", 04-API §6, blueprint D-39).
 * <p>
 * <b>Constant-time comparison.</b> {@code presented.equals(expected)} stops at the first different
 * character, so the response time leaks how many leading characters were right - an attacker can
 * guess a token byte by byte by measuring. Here both values are first hashed with SHA-256 (fixed
 * 32-byte length, so not even the token length leaks) and compared with
 * {@link MessageDigest#isEqual}, which always inspects every byte.
 * <p>
 * <b>No database work for strangers.</b> This runs <em>before</em> the caller is authenticated, so
 * everything it does can be triggered by anybody who reaches the port. The registry of sources is
 * therefore read as a whole, at most every {@link #SOURCE_CACHE_TTL}, into an immutable in-memory
 * snapshot: a flood of requests to {@code /api/v1/integrations/<random>/webhook} costs a map lookup,
 * not a database connection each (review finding "webhook DoS").
 * <p>
 * <b>What the caller learns.</b> A path naming no registered source is answered exactly like a wrong
 * token ({@link Outcome#REJECTED}, 401), so the endpoint cannot be used to enumerate source codes.
 * A registered source that cannot accept deliveries (disabled, unsupported auth type, missing /
 * short / placeholder token) answers 503 {@code INTEGRATION_UNAVAILABLE} (D-39: the operator must fix
 * the configuration, and a weak token is never accepted just because it was configured) - with a
 * generic message; the precise reason is only logged on the server ({@link Result#reason()}).
 * <p>
 * <b>Bounded metric tags.</b> {@link Result#metricSource()} is the code of a <em>registered</em>
 * source or {@link #UNKNOWN_SOURCE}, never the raw path segment: every distinct tag value is a new
 * Micrometer meter that lives forever, so tagging with attacker-controlled text would let anybody
 * grow the backend's memory and the Prometheus TSDB without limit.
 */
@Service
public class WebhookAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(WebhookAuthenticator.class);

    static final int MIN_TOKEN_LENGTH = 32;
    static final String PLACEHOLDER_PREFIX = "change-me";
    /** How long the in-memory copy of {@code integration_sources} is trusted before it is re-read. */
    static final Duration SOURCE_CACHE_TTL = Duration.ofSeconds(30);
    /** Metric/log value used when the path names no registered source. */
    public static final String UNKNOWN_SOURCE = "unknown";
    private static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

    private final IntegrationSourceRepository sources;
    private final SecretResolver secrets;
    private final Clock clock;
    private final AtomicReference<Instant> lastWarning = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public WebhookAuthenticator(IntegrationSourceRepository sources, SecretResolver secrets, Clock clock) {
        this.sources = sources;
        this.secrets = secrets;
        this.clock = clock;
    }

    /** Outcome of {@link #authenticate}. */
    public enum Outcome {
        AUTHENTICATED,
        /** wrong or missing token, or no such source -> 401 */
        REJECTED,
        /** registered source that cannot accept deliveries (disabled / misconfigured) -> 503 */
        UNAVAILABLE
    }

    /**
     * @param source       set when {@link Outcome#AUTHENTICATED}
     * @param metricSource bounded tag value: a registered source code or {@link #UNKNOWN_SOURCE}
     * @param message      safe to return to the client (generic, never contains a secret)
     * @param reason       precise cause for the server log only ({@code null} when authenticated)
     */
    public record Result(Outcome outcome, IntegrationSourceRef source, String metricSource, String message,
                         String reason) {

        static Result authenticated(IntegrationSourceRef source) {
            return new Result(Outcome.AUTHENTICATED, source, source.code(), null, null);
        }

        static Result rejected(String metricSource, String reason) {
            return new Result(Outcome.REJECTED, null, metricSource, "Missing or invalid integration token", reason);
        }

        static Result unavailable(String metricSource, String reason) {
            return new Result(Outcome.UNAVAILABLE, null, metricSource,
                    "The integration source is not available; ask an administrator to check its configuration",
                    reason);
        }
    }

    /**
     * @param sourceCode     path segment of {@code /api/v1/integrations/{code}/webhook} (untrusted)
     * @param presentedToken the bearer token of the request, {@code null} when absent
     */
    public Result authenticate(String sourceCode, String presentedToken) {
        RegisteredSource source = sourceCode == null ? null : registry().get(sourceCode);
        if (source == null) {
            // Same answer as a wrong token: the endpoint must not reveal which source codes exist.
            return Result.rejected(UNKNOWN_SOURCE, "no registered source for this path");
        }
        if (!source.enabled()) {
            return Result.unavailable(source.code(), "source is disabled");
        }
        if (source.authType() != IntegrationAuthType.TOKEN) {
            return Result.unavailable(source.code(), "auth type " + source.authType() + " is not supported by this webhook");
        }
        Optional<String> secret = secrets.resolve(source.secretRef());
        if (secret.isEmpty() || !usable(secret.get())) {
            warnMisconfigured(source);
            return Result.unavailable(source.code(), "no usable token configured (" + source.secretRef() + ")");
        }
        if (!tokensMatch(presentedToken, secret.get())) {
            return Result.rejected(source.code(), presentedToken == null ? "no bearer token" : "wrong token");
        }
        return Result.authenticated(new IntegrationSourceRef(source.id(), source.code(), source.organizationId(),
                source.sourceType()));
    }

    /**
     * Forgets the in-memory registry so the next request re-reads {@code integration_sources}. For
     * tests today; the integration-source admin API (Sprint 3) will call it after a change.
     */
    public void invalidate() {
        snapshot.set(null);
    }

    /** SHA-256 both, then compare every byte (see class comment). {@code null} never matches. */
    static boolean tokensMatch(String presented, String expected) {
        if (presented == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(presented), sha256(expected));
    }

    static boolean usable(String token) {
        return token.length() >= MIN_TOKEN_LENGTH
                && !token.trim().toLowerCase(Locale.ROOT).startsWith(PLACEHOLDER_PREFIX);
    }

    /**
     * The registry, re-read at most every {@link #SOURCE_CACHE_TTL}. If PostgreSQL is briefly
     * unreachable the previous copy keeps serving (sources change rarely); without any copy the
     * error propagates.
     */
    private Map<String, RegisteredSource> registry() {
        Instant now = clock.instant();
        Snapshot current = snapshot.get();
        if (current != null && now.isBefore(current.loadedAt().plus(SOURCE_CACHE_TTL))) {
            return current.byCode();
        }
        try {
            List<IntegrationSource> rows = sources.findAll();
            Map<String, RegisteredSource> byCode = rows.stream()
                    .map(RegisteredSource::of)
                    .collect(Collectors.toUnmodifiableMap(RegisteredSource::code, Function.identity(), (a, b) -> a));
            snapshot.set(new Snapshot(byCode, now));
            return byCode;
        }
        catch (DataAccessException ex) {
            if (current == null) {
                throw ex;
            }
            log.warn("Could not refresh integration sources ({}); using the copy from {}",
                    ex.getClass().getSimpleName(), current.loadedAt());
            return current.byCode();
        }
    }

    private void warnMisconfigured(RegisteredSource source) {
        Instant now = clock.instant();
        Instant previous = lastWarning.get();
        if (Duration.between(previous, now).compareTo(WARN_INTERVAL) >= 0 && lastWarning.compareAndSet(previous, now)) {
            // The reference is logged (it is not secret), the value never is.
            log.error("Webhook of integration source '{}' is unavailable: secret {} is missing, shorter than {} "
                    + "characters or a placeholder. Set it (scripts/dev-up.* generates one).",
                    source.code(), source.secretRef(), MIN_TOKEN_LENGTH);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /** Immutable copy of one {@code integration_sources} row - detached from JPA on purpose. */
    record RegisteredSource(UUID id, String code, UUID organizationId, IntegrationSourceType sourceType,
                            IntegrationAuthType authType, String secretRef, boolean enabled) {

        static RegisteredSource of(IntegrationSource row) {
            return new RegisteredSource(row.getId(), row.getCode(), row.getOrganizationId(), row.getSourceType(),
                    row.getAuthType(), row.getSecretRef(), row.isEnabled());
        }
    }

    private record Snapshot(Map<String, RegisteredSource> byCode, Instant loadedAt) {
    }
}
