package com.opscenter.shared.infrastructure.status;

import java.util.LinkedHashMap;
import java.util.Map;

import com.opscenter.shared.application.status.ComponentProbe;
import com.opscenter.shared.application.status.ProbeErrors;
import com.opscenter.shared.application.status.ProbeResult;
import com.opscenter.shared.application.storage.ObjectStorage;
import com.opscenter.shared.application.storage.ObjectStorageUnavailableException;
import com.opscenter.shared.infrastructure.storage.DisabledObjectStorage;

import org.springframework.stereotype.Component;

/**
 * MinIO / S3: {@code HEAD bucket}. The S3 protocol has no "server version" call, so
 * {@code version} stays {@code null} (D-63); bucket and endpoint are shown instead.
 */
@Component
public class ObjectStorageProbe implements ComponentProbe {

    private final ObjectStorage storage;

    public ObjectStorageProbe(ObjectStorage storage) {
        this.storage = storage;
    }

    @Override
    public String name() {
        return "minio";
    }

    @Override
    public ProbeResult probe() {
        if (storage instanceof DisabledObjectStorage) {
            return ProbeResult.unknown("Object storage disabled (opscenter.storage.enabled=false)");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("bucket", storage.bucket());
        details.put("endpoint", storage.endpoint());
        try {
            storage.checkAvailable();
            return ProbeResult.up(null, details);
        }
        catch (ObjectStorageUnavailableException ex) {
            return ProbeResult.down(ProbeErrors.sanitize(ex.getMessage()), details);
        }
    }
}
