package com.opscenter.shared.infrastructure.storage;

import com.opscenter.shared.application.storage.ObjectStorage;
import com.opscenter.shared.application.storage.ObjectStorageUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Creates the raw-payload bucket and its retention rule once the application is up (blueprint D-42),
 * so a fresh MinIO needs no manual {@code mc mb}. MinIO being down at that moment is not fatal -
 * object storage is optional (05-DEPLOY §8): the adapter retries at the first write.
 */
@Component
public class ObjectStorageBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageBootstrap.class);

    private final ObjectStorage storage;

    public ObjectStorageBootstrap(ObjectStorage storage) {
        this.storage = storage;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prepareBucket() {
        try {
            storage.ensureBucket();
        }
        catch (ObjectStorageUnavailableException ex) {
            log.warn("Object storage not ready at start-up ({}); the bucket will be created at the first write",
                    ex.getMessage());
        }
    }
}
