package com.opscenter.shared.application.status;

import java.util.regex.Pattern;

/**
 * Turns an exception into the short {@code error} text of a component (D-63): the most specific
 * cause, at most 200 characters, and any {@code user:password@} part of a URL masked - an
 * administrator page must never display a credential, even one embedded in a connection string.
 */
public final class ProbeErrors {

    private static final int MAX_LENGTH = 200;
    private static final Pattern URL_USER_INFO = Pattern.compile("://[^/@\\s]+@");

    private ProbeErrors() {
    }

    public static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName()
                : root.getClass().getSimpleName() + ": " + root.getMessage();
        return sanitize(message);
    }

    public static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        String masked = URL_USER_INFO.matcher(message.replaceAll("\\s+", " ").trim()).replaceAll("://***@");
        return masked.length() <= MAX_LENGTH ? masked : masked.substring(0, MAX_LENGTH - 3) + "...";
    }
}
