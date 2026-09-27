package com.opscenter.audit.domain;

import java.util.Locale;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * Last line of defence for TC-AUD-004 / 03-DB §30 "audit must never store a secret or token".
 * <p>
 * The convention is that callers pass DTO snapshots, never entities. This guard makes the
 * convention enforceable: any JSON property whose name looks like a credential makes the audit
 * write fail (and therefore the business transaction roll back) rather than silently persisting a
 * hash or token. Matching is on exact, case-insensitive names, so {@code tokenCount} or
 * {@code passwordChangedAt} stay allowed.
 */
public final class SensitiveDataGuard {

    private static final Set<String> FORBIDDEN_PROPERTY_NAMES = Set.of(
            "password", "passwordhash", "password_hash", "rawpassword", "newpassword", "oldpassword",
            "secret", "secretkey", "secret_key", "clientsecret", "client_secret",
            "token", "accesstoken", "access_token", "refreshtoken", "refresh_token",
            "tokenhash", "token_hash", "sessionkeyhash", "session_key_hash",
            "credential", "credentials", "apikey", "api_key", "authorization", "privatekey", "private_key");

    private SensitiveDataGuard() {
    }

    /**
     * @throws IllegalArgumentException when a forbidden property name appears anywhere in the tree
     */
    public static void assertNoSensitiveData(JsonNode node, String which) {
        if (node == null) {
            return;
        }
        String offender = findOffender(node, "");
        if (offender != null) {
            throw new IllegalArgumentException("Audit snapshot '" + which + "' contains sensitive property '"
                    + offender + "'. Pass a DTO without credentials (TC-AUD-004).");
        }
    }

    private static String findOffender(JsonNode node, String path) {
        if (node.isObject()) {
            for (var entry : node.properties()) {
                String name = entry.getKey();
                String fullPath = path.isEmpty() ? name : path + "." + name;
                if (FORBIDDEN_PROPERTY_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                    return fullPath;
                }
                String nested = findOffender(entry.getValue(), fullPath);
                if (nested != null) {
                    return nested;
                }
            }
        }
        else if (node.isArray()) {
            int index = 0;
            for (JsonNode child : node) {
                String nested = findOffender(child, path + "[" + index++ + "]");
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
