package com.opscenter.shared.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * The one severity scale of OpsCenter, shared by alerts and incidents (01-SRS §6, blueprint D-45).
 * <p>
 * {@code P1} is the most severe. One enum for both modules means a filter such as
 * {@code severity=P1} or a sort by severity means the same thing on {@code /alerts} and on
 * {@code /incidents}, and an incident can inherit the severity of its triggering alert without a
 * mapping table. The declaration order matters: it is the ranking (see {@link #isMoreSevereThan})
 * and, because the codes sort alphabetically in the same order, {@code ORDER BY severity} in SQL
 * gives "most severe first" too.
 */
public enum Severity {
    P1,
    P2,
    P3,
    P4;

    /** {@code true} when this severity ranks strictly above {@code other} (P1 above P2 ...); {@code null} ranks lowest. */
    public boolean isMoreSevereThan(Severity other) {
        return other == null || ordinal() < other.ordinal();
    }

    /** The more severe of the two ({@code null} is ignored). */
    public static Severity mostSevere(Severity first, Severity second) {
        if (first == null) {
            return second;
        }
        return second != null && second.isMoreSevereThan(first) ? second : first;
    }

    /** Parses {@code "p1"} / {@code "P1"}; empty for anything else. */
    public static Optional<Severity> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        }
        catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
