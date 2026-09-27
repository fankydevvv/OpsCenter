package com.opscenter.shared.infrastructure.storage;

import com.opscenter.shared.application.storage.ObjectStorage;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * {@code minio} component of {@code /actuator/health} (blueprint D-62), next to Boot's own
 * {@code db}, {@code redis} and {@code rabbit}.
 * <p>
 * Boot names a health contributor after its bean name minus the {@code HealthIndicator} suffix,
 * hence the explicit bean name {@code minioHealthIndicator}. The check is a {@code HEAD bucket}
 * with a 2 s limit. The indicator is <b>not</b> part of the readiness group (only
 * {@code readinessState,db} are): a MinIO outage shows here but never makes the container
 * unhealthy, because ingestion goes on without the archive.
 */
@Component("minioHealthIndicator")
public class ObjectStorageHealthIndicator extends AbstractHealthIndicator {

    private final ObjectStorage storage;

    public ObjectStorageHealthIndicator(ObjectStorage storage) {
        super("Object storage health check failed");
        this.storage = storage;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        if (storage instanceof DisabledObjectStorage) {
            builder.unknown().withDetail("enabled", false);
            return;
        }
        storage.checkAvailable();
        builder.up()
                .withDetail("bucket", storage.bucket())
                .withDetail("endpoint", storage.endpoint());
    }
}
