package com.opscenter.alert.application;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Tells a <em>lost concurrency race</em> apart from a deterministic data error (blueprint D-51).
 * <p>
 * The ingestion's last line of defence are two partial unique indexes: at most one FIRING alert per
 * fingerprint ({@code uk_alerts_firing_fingerprint}) and at most one open incident per correlation
 * key ({@code uk_incidents_open_correlation}). If the Redis lock was lost (R-23) and two deliveries
 * really raced, one of them fails on exactly these indexes - retrying it once takes the dedup/link
 * path and succeeds. Any other integrity error (a value too long - SQLState 22001, a NOT NULL, a
 * foreign key ...) would fail the same way on every retry; retrying it only hides a bug, and
 * answering it with the generic 409 would make Alertmanager drop the whole delivery (it does not
 * retry 4xx). Such errors are therefore reported, not retried.
 */
public final class IngestionRaces {

    /** PostgreSQL "unique_violation". */
    static final String UNIQUE_VIOLATION = "23505";

    /** The unique indexes that serialise concurrent ingestion (V009, V010). */
    static final Set<String> RACE_GUARDS = Set.of("uk_alerts_firing_fingerprint", "uk_incidents_open_correlation");

    private IngestionRaces() {
    }

    /**
     * {@code true} for an optimistic-lock failure or a unique violation of one of the
     * {@link #RACE_GUARDS}; when the driver does not name the constraint, any unique violation counts
     * (a harmless extra retry at worst).
     */
    public static boolean isLostRace(RuntimeException failure) {
        if (failure instanceof OptimisticLockingFailureException) {
            return true;
        }
        if (!(failure instanceof DataIntegrityViolationException)) {
            return false;
        }
        if (!UNIQUE_VIOLATION.equals(sqlState(failure))) {
            return false;
        }
        String constraint = constraintName(failure);
        return constraint == null || RACE_GUARDS.contains(constraint.toLowerCase(Locale.ROOT));
    }

    /** SQLState of the first {@link SQLException} in the cause chain, or {@code null}. */
    public static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** Violated constraint as reported by Hibernate, or {@code null}. */
    public static String constraintName(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }
}
