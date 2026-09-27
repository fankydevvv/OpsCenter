package com.opscenter.shared.infrastructure.storage;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.shared.application.storage.ContentHashes;
import com.opscenter.shared.application.storage.ObjectStorage;
import com.opscenter.shared.application.storage.StoredObject;
import com.opscenter.shared.application.storage.StoredObjectRef;
import com.opscenter.support.AbstractIntegrationTest;
import com.opscenter.support.MinioTestcontainersConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Blueprint D-41/D-42 against a real MinIO (the same image as docker-compose.yml): bucket bootstrap
 * with the 180-day retention rule, content-addressed idempotent writes, reads with metadata,
 * missing keys, and lazy creation of a bucket that did not exist at start-up.
 */
class S3ObjectStorageIT extends AbstractIntegrationTest {

    @Autowired ObjectStorage storage;
    @Autowired S3Client s3;
    @Autowired ObjectStorageProperties properties;

    @Test
    void bootstrapCreatedTheBucket_withTheRetentionLifecycleRule() {
        assertThat(storage.bucket()).isEqualTo(MinioTestcontainersConfiguration.BUCKET);
        storage.checkAvailable();

        GetBucketLifecycleConfigurationResponse lifecycle = s3.getBucketLifecycleConfiguration(
                request -> request.bucket(storage.bucket()));
        LifecycleRule rule = lifecycle.rules().stream()
                .filter(r -> S3ObjectStorage.RETENTION_RULE_ID.equals(r.id())).findFirst().orElseThrow();
        assertThat(rule.expiration().days()).isEqualTo(180);
        // D-42: only the archive prefix expires, not every object of the bucket
        assertThat(rule.filter().prefix()).isEqualTo("alertmanager/");
    }

    @Test
    void retentionBootstrap_keepsLifecycleRulesItDoesNotOwn() {
        String bucket = "it-rules-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        LifecycleRule operatorRule = LifecycleRule.builder().id("operator-tmp-cleanup").status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix("tmp/").build())
                .expiration(LifecycleExpiration.builder().days(7).build()).build();
        s3.putBucketLifecycleConfiguration(request -> request.bucket(bucket)
                .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(operatorRule).build()));
        ObjectStorageProperties props = new ObjectStorageProperties(true, properties.endpoint(), properties.accessKey(),
                properties.secretKey(), bucket, "us-east-1", true, Duration.ofDays(180), Duration.ofSeconds(2),
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(2), "alertmanager/");

        new S3ObjectStorage(s3, props, Clock.systemUTC()).ensureBucket();
        new S3ObjectStorage(s3, props, Clock.systemUTC()).ensureBucket();   // a restart: idempotent upsert

        List<LifecycleRule> rules = s3.getBucketLifecycleConfiguration(request -> request.bucket(bucket)).rules();
        assertThat(rules).extracting(LifecycleRule::id)
                .containsExactlyInAnyOrder("operator-tmp-cleanup", S3ObjectStorage.RETENTION_RULE_ID);
    }

    @Test
    void contentAddressedPut_isIdempotent_andReadableWithMetadata() {
        byte[] body = ("{\"receiver\":\"opscenter\",\"n\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
        String sha256 = ContentHashes.sha256Hex(body);

        StoredObjectRef first = storage.putContentAddressed("alertmanager", ".json", body, "application/json",
                Map.of("source", "alertmanager", "Request-Id", "req-42"));
        StoredObjectRef second = storage.putContentAddressed("/alertmanager/", ".json", body, "application/json", Map.of());

        assertThat(first.key()).matches("alertmanager/\\d{4}/\\d{2}/\\d{2}/" + sha256 + "\\.json");
        assertThat(second).isEqualTo(first);
        assertThat(first.sha256()).isEqualTo(sha256);
        assertThat(first.sizeBytes()).isEqualTo(body.length);
        assertThat(storage.exists(first.key())).isTrue();

        StoredObject read = storage.get(first.key()).orElseThrow();
        assertThat(read.content()).isEqualTo(body);
        assertThat(read.contentType()).startsWith("application/json");
        assertThat(read.ref()).isEqualTo(first);
        assertThat(read.metadata()).containsEntry("source", "alertmanager").containsEntry("request-id", "req-42");
    }

    @Test
    void missingKeys_areEmptyNotErrors() {
        assertThat(storage.get("alertmanager/2000/01/01/does-not-exist.json")).isEmpty();
        assertThat(storage.exists("alertmanager/2000/01/01/does-not-exist.json")).isFalse();
    }

    @Test
    void aBucketMissingAtStartUp_isCreatedByTheFirstWrite() {
        ObjectStorageProperties otherBucket = new ObjectStorageProperties(true, properties.endpoint(),
                properties.accessKey(), properties.secretKey(), "it-lazy-" + UUID.randomUUID().toString().substring(0, 8),
                "us-east-1", true, Duration.ofDays(30), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(2), "generic/");
        Instant fixed = Instant.parse("2026-01-02T03:04:05Z");
        S3ObjectStorage lazy = new S3ObjectStorage(s3, otherBucket, Clock.fixed(fixed, ZoneOffset.UTC));
        byte[] body = "raw".getBytes(StandardCharsets.UTF_8);

        StoredObjectRef ref = lazy.putContentAddressed("generic", ".txt", body, "text/plain", null);

        assertThat(ref.bucket()).isEqualTo(otherBucket.bucket());
        assertThat(ref.key()).isEqualTo("generic/2026/01/02/" + ContentHashes.sha256Hex(body) + ".txt");
        lazy.checkAvailable();
        Optional<StoredObject> read = lazy.get(ref.key());
        assertThat(read).map(StoredObject::content).contains(body);
    }
}
