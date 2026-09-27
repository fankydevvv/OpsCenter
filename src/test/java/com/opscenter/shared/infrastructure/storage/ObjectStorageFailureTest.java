package com.opscenter.shared.infrastructure.storage;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import com.opscenter.shared.application.storage.ObjectStorageUnavailableException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What callers see when object storage is unreachable or disabled (D-42, D-62): a 503-mapped
 * {@link ObjectStorageUnavailableException} with a short message that never contains the secret key,
 * a DOWN/UNKNOWN health component, and a bootstrap that only logs. Port 1 refuses connections at once,
 * so no Docker is needed.
 */
class ObjectStorageFailureTest {

    private static final String SECRET = "super-secret-key-123";

    private final ObjectStorageProperties unreachable = new ObjectStorageProperties(true, "http://127.0.0.1:1",
            "access", SECRET, "opscenter-raw", "us-east-1", true, Duration.ofDays(180), Duration.ofMillis(500),
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(1), "alertmanager/");
    private final S3Client s3 = new ObjectStorageConfig.S3StorageConfig().objectStorageS3Client(unreachable);
    private final S3ObjectStorage storage = new S3ObjectStorage(s3, unreachable, Clock.systemUTC());

    @AfterEach
    void close() {
        s3.close();
    }

    @Test
    void everyOperation_failsWithObjectStorageUnavailable_withoutLeakingTheSecret() {
        assertThatThrownBy(() -> storage.putContentAddressed("alertmanager", ".json", new byte[] {1}, "application/json", Map.of()))
                .isInstanceOf(ObjectStorageUnavailableException.class)
                .hasMessageNotContaining(SECRET)
                .extracting("code").isEqualTo(ObjectStorageUnavailableException.CODE);
        assertThatThrownBy(() -> storage.get("k")).isInstanceOf(ObjectStorageUnavailableException.class);
        assertThatThrownBy(storage::checkAvailable)
                .isInstanceOf(ObjectStorageUnavailableException.class)
                .hasMessageContaining("reach bucket opscenter-raw");
        assertThat(unreachable.toString()).doesNotContain(SECRET).contains("secretKey=***");
    }

    @Test
    void healthIndicator_isDownWhenUnreachable_andUnknownWhenDisabled() {
        Health down = new ObjectStorageHealthIndicator(storage).health();
        assertThat(down.getStatus()).isEqualTo(Status.DOWN);

        Health disabled = new ObjectStorageHealthIndicator(new DisabledObjectStorage("opscenter-raw")).health();
        assertThat(disabled.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(disabled.getDetails()).containsEntry("enabled", false);
    }

    @Test
    void bootstrap_onlyLogsWhenTheStoreIsDown() {
        assertThatCode(() -> new ObjectStorageBootstrap(storage).prepareBucket()).doesNotThrowAnyException();
    }

    @Test
    void metadata_isSanitisedToS3Rules() {
        assertThat(S3ObjectStorage.sanitize(Map.of("Request Id", "café", "source", "x".repeat(300))))
                .containsEntry("request-id", "caf?")
                .hasEntrySatisfying("source", value -> assertThat(value).hasSize(256));
        assertThat(S3ObjectStorage.sanitize(null)).isEmpty();
    }
}
