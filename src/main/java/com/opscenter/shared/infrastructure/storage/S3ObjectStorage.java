package com.opscenter.shared.infrastructure.storage;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.opscenter.shared.application.storage.ContentHashes;
import com.opscenter.shared.application.storage.ObjectStorage;
import com.opscenter.shared.application.storage.ObjectStorageUnavailableException;
import com.opscenter.shared.application.storage.StoredObject;
import com.opscenter.shared.application.storage.StoredObjectRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link ObjectStorage} over the S3 protocol with the AWS SDK for Java v2 (blueprint D-41, D-42).
 * <p>
 * Design points a reader should notice:
 * <ul>
 *   <li><b>Content addressing</b>: {@link #putContentAddressed} names the object after the SHA-256
 *       of its bytes and skips the upload when {@code HEAD} finds it - retries are idempotent and
 *       identical payloads are stored once.</li>
 *   <li><b>Lazy bucket creation</b>: {@link ObjectStorageBootstrap} creates the bucket at start-up;
 *       if MinIO was down then, the first write notices the missing bucket and creates it.</li>
 *   <li><b>Error translation</b>: every {@link SdkException} (network, timeout, S3 error) becomes
 *       {@link ObjectStorageUnavailableException} with a message built here - SDK messages can be
 *       long and are never forwarded verbatim; no credential is ever part of one.</li>
 *   <li>A missing key is not an error: {@link #get} returns empty and {@link #exists} false.</li>
 *   <li><b>Short budget for the archive</b>: the HEAD and PUT of {@link #putContentAddressed} run inside
 *       a webhook that Alertmanager gives up on after 10 s, so each gets {@code archive-timeout} (2 s)
 *       instead of the general call timeout - a <em>hanging</em> store (not just a refusing one) then
 *       still means "archived=false and 200", not a timeout followed by a retry storm.</li>
 *   <li><b>Retention rule owned, not the lifecycle</b>: the 180-day rule is scoped to the archive
 *       prefix and merged into the bucket's existing lifecycle by rule id, so rules an operator added
 *       survive every start-up.</li>
 * </ul>
 */
public class S3ObjectStorage implements ObjectStorage {

    /** Name of the lifecycle rule this class owns in the bucket. */
    static final String RETENTION_RULE_ID = "opscenter-raw-retention";

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);
    private static final DateTimeFormatter DAY_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_METADATA_VALUE = 256;

    private final S3Client s3;
    private final ObjectStorageProperties properties;
    private final Clock clock;
    private volatile boolean bucketReady;

    public S3ObjectStorage(S3Client s3, ObjectStorageProperties properties, Clock clock) {
        this.s3 = s3;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String bucket() {
        return properties.bucket();
    }

    @Override
    public String endpoint() {
        return properties.endpoint();
    }

    @Override
    public StoredObjectRef putContentAddressed(String prefix, String extension, byte[] content, String contentType,
                                               Map<String, String> metadata) {
        String sha256 = ContentHashes.sha256Hex(content);
        String key = trimSlashes(prefix) + "/" + DAY_PATH.format(clock.instant()) + "/" + sha256
                + (extension == null ? "" : extension);
        Duration budget = properties.archiveTimeout();
        if (exists(key, budget)) {
            log.debug("Object {} already stored; upload skipped (content-addressed)", key);
            return new StoredObjectRef(bucket(), key, sha256, content.length);
        }
        return put(key, content, contentType, metadata, budget);
    }

    @Override
    public StoredObjectRef put(String key, byte[] content, String contentType, Map<String, String> metadata) {
        return put(key, content, contentType, metadata, null);
    }

    /** @param callTimeout per-call budget, {@code null} = the client's {@code api-call-timeout} */
    private StoredObjectRef put(String key, byte[] content, String contentType, Map<String, String> metadata,
                                Duration callTimeout) {
        ensureBucketLazily();
        PutObjectRequest.Builder builder = PutObjectRequest.builder()
                .bucket(bucket())
                .key(key)
                .contentType(contentType)
                .metadata(sanitize(metadata));
        if (callTimeout != null) {
            builder.overrideConfiguration(o -> o.apiCallTimeout(callTimeout));
        }
        PutObjectRequest request = builder.build();
        try {
            s3.putObject(request, RequestBody.fromBytes(content));
        }
        catch (NoSuchBucketException missing) {
            // The bucket disappeared (volume wiped, manual delete): recreate it once and retry.
            bucketReady = false;
            ensureBucket();
            try {
                s3.putObject(request, RequestBody.fromBytes(content));
            }
            catch (SdkException ex) {
                throw unavailable("store object " + key, ex);
            }
        }
        catch (SdkException ex) {
            throw unavailable("store object " + key, ex);
        }
        return new StoredObjectRef(bucket(), key, ContentHashes.sha256Hex(content), content.length);
    }

    @Override
    public Optional<StoredObject> get(String key) {
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket()).key(key).build());
            byte[] content = bytes.asByteArray();
            GetObjectResponse response = bytes.response();
            return Optional.of(new StoredObject(
                    new StoredObjectRef(bucket(), key, ContentHashes.sha256Hex(content), content.length),
                    response.contentType(), response.metadata(), content));
        }
        catch (NoSuchKeyException | NoSuchBucketException missing) {
            return Optional.empty();
        }
        catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw unavailable("read object " + key, ex);
        }
        catch (SdkException ex) {
            throw unavailable("read object " + key, ex);
        }
    }

    @Override
    public boolean exists(String key) {
        return exists(key, null);
    }

    private boolean exists(String key, Duration callTimeout) {
        HeadObjectRequest.Builder request = HeadObjectRequest.builder().bucket(bucket()).key(key);
        if (callTimeout != null) {
            request.overrideConfiguration(o -> o.apiCallTimeout(callTimeout));
        }
        try {
            s3.headObject(request.build());
            return true;
        }
        catch (NoSuchKeyException | NoSuchBucketException missing) {
            return false;
        }
        catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return false;
            }
            throw unavailable("check object " + key, ex);
        }
        catch (SdkException ex) {
            throw unavailable("check object " + key, ex);
        }
    }

    @Override
    public void ensureBucket() {
        try {
            if (!bucketExists()) {
                try {
                    s3.createBucket(request -> request.bucket(bucket()));
                    log.info("Created object storage bucket '{}' at {}", bucket(), endpoint());
                }
                catch (BucketAlreadyOwnedByYouException raceLost) {
                    // another instance created it a moment ago - fine
                }
            }
            applyRetention();
            bucketReady = true;
        }
        catch (SdkException ex) {
            throw unavailable("prepare bucket " + bucket(), ex);
        }
    }

    @Override
    public void checkAvailable() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket())
                    .overrideConfiguration(o -> o.apiCallTimeout(CHECK_TIMEOUT))
                    .build());
        }
        catch (NoSuchBucketException missing) {
            throw new ObjectStorageUnavailableException("Bucket '" + bucket() + "' does not exist", missing);
        }
        catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                throw new ObjectStorageUnavailableException("Bucket '" + bucket() + "' does not exist", ex);
            }
            throw unavailable("reach bucket " + bucket(), ex);
        }
        catch (SdkException ex) {
            throw unavailable("reach bucket " + bucket(), ex);
        }
    }

    private boolean bucketExists() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket()).build());
            return true;
        }
        catch (NoSuchBucketException missing) {
            return false;
        }
        catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * 03-DB §25: raw payloads are kept 90-180 days. S3 lifecycle rules let the store delete old
     * objects itself - no cleanup job in the backend. Best effort: a store without lifecycle
     * support only logs a warning.
     * <p>
     * {@code PutBucketLifecycleConfiguration} <em>replaces</em> the whole configuration, so the current
     * rules are read first and only the rule with our id is replaced (upsert); the rule is limited to
     * the archive prefix ({@code raw-retention-prefix}, D-42) instead of the whole bucket.
     */
    private void applyRetention() {
        int days = (int) Math.max(1, properties.rawRetention().toDays());
        LifecycleRule ours = LifecycleRule.builder()
                .id(RETENTION_RULE_ID)
                .status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix(properties.rawRetentionPrefix()).build())
                .expiration(LifecycleExpiration.builder().days(days).build())
                .build();
        try {
            List<LifecycleRule> rules = new ArrayList<>(currentLifecycleRules());
            rules.removeIf(rule -> RETENTION_RULE_ID.equals(rule.id()));
            rules.add(ours);
            s3.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
                    .bucket(bucket())
                    .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(rules).build())
                    .build());
        }
        catch (SdkException ex) {
            log.warn("Could not apply the {}-day retention rule to bucket '{}': {}", days, bucket(),
                    ex.getClass().getSimpleName());
        }
    }

    /** Rules already on the bucket; none (404 NoSuchLifecycleConfiguration) is an empty list. */
    private List<LifecycleRule> currentLifecycleRules() {
        try {
            return s3.getBucketLifecycleConfiguration(request -> request.bucket(bucket())).rules();
        }
        catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return List.of();
            }
            throw ex;
        }
    }

    private void ensureBucketLazily() {
        if (!bucketReady) {
            ensureBucket();
        }
    }

    private ObjectStorageUnavailableException unavailable(String action, SdkException ex) {
        String reason = ex instanceof S3Exception s3Error
                ? "HTTP " + s3Error.statusCode() + (s3Error.awsErrorDetails() == null ? ""
                        : " " + s3Error.awsErrorDetails().errorCode())
                : ex.getClass().getSimpleName();
        return new ObjectStorageUnavailableException("Object storage could not " + action + " (" + reason + ")", ex);
    }

    /** S3 user metadata must be ASCII; keys are lower-cased, values truncated. */
    static Map<String, String> sanitize(Map<String, String> metadata) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (metadata == null) {
            return clean;
        }
        metadata.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String safeKey = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
            String safeValue = value.replaceAll("[^\\x20-\\x7E]", "?");
            clean.put(safeKey, safeValue.length() > MAX_METADATA_VALUE ? safeValue.substring(0, MAX_METADATA_VALUE) : safeValue);
        });
        return clean;
    }

    private static String trimSlashes(String prefix) {
        String trimmed = prefix == null ? "" : prefix.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "objects" : trimmed;
    }
}
