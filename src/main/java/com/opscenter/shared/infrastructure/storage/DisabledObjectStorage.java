package com.opscenter.shared.infrastructure.storage;

import java.util.Map;
import java.util.Optional;

import com.opscenter.shared.application.storage.ObjectStorage;
import com.opscenter.shared.application.storage.ObjectStorageUnavailableException;
import com.opscenter.shared.application.storage.StoredObject;
import com.opscenter.shared.application.storage.StoredObjectRef;

/**
 * Stand-in used when {@code opscenter.storage.enabled=false} (MinIO is optional, 05-DEPLOY §8):
 * every write/read reports "unavailable", which callers already handle (the webhook continues with
 * {@code archived = false}), and the health indicator shows the component as UNKNOWN instead of DOWN.
 */
public class DisabledObjectStorage implements ObjectStorage {

    private final String bucket;

    public DisabledObjectStorage(String bucket) {
        this.bucket = bucket;
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public String endpoint() {
        return null;
    }

    @Override
    public StoredObjectRef putContentAddressed(String prefix, String extension, byte[] content, String contentType,
                                               Map<String, String> metadata) {
        throw disabled();
    }

    @Override
    public StoredObjectRef put(String key, byte[] content, String contentType, Map<String, String> metadata) {
        throw disabled();
    }

    @Override
    public Optional<StoredObject> get(String key) {
        throw disabled();
    }

    @Override
    public boolean exists(String key) {
        throw disabled();
    }

    @Override
    public void ensureBucket() {
        // nothing to prepare
    }

    @Override
    public void checkAvailable() {
        throw disabled();
    }

    private static ObjectStorageUnavailableException disabled() {
        return new ObjectStorageUnavailableException("Object storage is disabled (opscenter.storage.enabled=false)", null);
    }
}
