package com.opscenter.shared.application.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 helper for content addressing and integrity checks (03-DB §3.5). Kept here so the storage
 * adapter, the webhook (idempotency key {@code sha256:<hex>}) and tests compute the same value.
 */
public final class ContentHashes {

    private ContentHashes() {
    }

    /** Lower-case hex SHA-256 of {@code content}. */
    public static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
