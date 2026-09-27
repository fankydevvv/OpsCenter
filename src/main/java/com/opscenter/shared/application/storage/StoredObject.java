package com.opscenter.shared.application.storage;

import java.util.Map;

/**
 * An object read back from {@link ObjectStorage}. Raw payloads are small (the webhook body is capped
 * at 1 MiB), so the content is held in memory.
 *
 * @param ref         bucket/key plus the SHA-256 and size computed from the bytes that were read
 * @param contentType the stored {@code Content-Type}
 * @param metadata    user metadata stored with the object
 * @param content     the bytes
 */
public record StoredObject(StoredObjectRef ref, String contentType, Map<String, String> metadata, byte[] content) {

    public StoredObject {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
