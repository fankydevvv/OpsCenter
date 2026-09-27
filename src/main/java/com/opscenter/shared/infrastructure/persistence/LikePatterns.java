package com.opscenter.shared.infrastructure.persistence;

import java.util.Locale;

/**
 * Builds safe {@code LIKE} patterns for "contains" searches ({@code q=} filters).
 * <p>
 * {@code %} and {@code _} are wildcards inside a {@code LIKE} pattern, so a user typing
 * {@code q=%} would match every row. The text is escaped with {@link #ESCAPE} and the same
 * character must be passed to {@code CriteriaBuilder.like(expr, pattern, ESCAPE)}.
 */
public final class LikePatterns {

    /** Escape character to pass as the third argument of {@code CriteriaBuilder.like}. */
    public static final char ESCAPE = '\\';

    private LikePatterns() {
    }

    /** {@code %text%} in lower case with wildcards neutralised. */
    public static String contains(String text) {
        return "%" + escape(text.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
