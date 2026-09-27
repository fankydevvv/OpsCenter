package com.opscenter.servicecatalog.domain;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.domain.InvalidRequestException;

/**
 * Canonical environment codes (blueprint D-33, 03-DB §38.2).
 * <p>
 * The specifications use both {@code PROD} (04-API §10) and {@code PRODUCTION} (03-DB §38.2), and
 * Prometheus labels are written by hand ({@code env="prod"}, {@code environment="Staging"}). If the
 * catalog stored whatever it received, "PROD" and "PRODUCTION" would become two environments and
 * an alert labelled {@code prod} would not find the {@code PRODUCTION} row. So every code - from
 * the API and from alert labels alike - goes through {@link #normalize}: trim, upper case,
 * {@code -}/space to {@code _}, then the alias table below. Only the canonical form is stored.
 */
public final class EnvironmentCode {

    public static final String DEV = "DEV";
    public static final String STAGING = "STAGING";
    public static final String PRODUCTION = "PRODUCTION";

    /** Alias -> canonical code (D-33). */
    public static final Map<String, String> ALIASES = Map.of(
            "PROD", PRODUCTION,
            "PRD", PRODUCTION,
            "STG", STAGING,
            "STAGE", STAGING,
            "DEVELOPMENT", DEV);

    public static final String REGEX = "^[A-Z][A-Z0-9_]{1,49}$";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private EnvironmentCode() {
    }

    /** Canonical form of {@code raw}; {@code null} or blank stays {@code null}. Does not validate. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return ALIASES.getOrDefault(upper, upper);
    }

    public static boolean isValid(String normalized) {
        return normalized != null && PATTERN.matcher(normalized).matches();
    }

    /** Normalises and validates a code supplied by a client; 400 when it does not fit {@link #REGEX}. */
    public static String require(String raw) {
        String code = normalize(raw);
        if (!isValid(code)) {
            throw new InvalidRequestException(ErrorCodes.VALIDATION_FAILED,
                    "Environment code must match " + REGEX + " after normalisation (e.g. DEV, STAGING, PRODUCTION)");
        }
        return code;
    }
}
