package com.opscenter.servicecatalog.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.domain.InvalidRequestException;

/**
 * Rules of {@code services.code} (blueprint D-33).
 * <p>
 * The code is the <b>join key</b> between a Prometheus alert and the catalog: a target scraped with
 * label {@code service="odoo-erp"} belongs to the service whose code is {@code odoo-erp}
 * (FR-ALT-05). That is why it is lower case (Prometheus label values are case sensitive and
 * lower case by convention), restricted to URL/label-friendly characters and immutable after
 * creation - renaming it would silently turn every alert of the service into an UNMAPPED one.
 * The same regex is a CHECK constraint in V007.
 */
public final class ServiceCode {

    public static final String REGEX = "^[a-z0-9][a-z0-9._-]{0,99}$";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private ServiceCode() {
    }

    /** Trim + lower case; {@code null} or blank stays {@code null}. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String normalized) {
        return normalized != null && PATTERN.matcher(normalized).matches();
    }

    /** Normalises and validates a code supplied by a client; 400 when it does not fit {@link #REGEX}. */
    public static String require(String raw) {
        String code = normalize(raw);
        if (!isValid(code)) {
            throw new InvalidRequestException(ErrorCodes.VALIDATION_FAILED,
                    "Service code must match " + REGEX + " (lower case letters, digits, '.', '_', '-')");
        }
        return code;
    }
}
