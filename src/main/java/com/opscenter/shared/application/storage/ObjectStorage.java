package com.opscenter.shared.application.storage;

import java.util.Map;
import java.util.Optional;

/**
 * Port for storing large or raw blobs outside PostgreSQL (03-DB §3.5 "store the reference, not the
 * blob"; 05-DEPLOY §8; blueprint D-41, D-42).
 * <p>
 * The database keeps only a {@link StoredObjectRef} ({@code bucket, key, sha256, sizeBytes}); the
 * bytes live in an S3-compatible object store (MinIO in DEV). The port is vendor neutral on
 * purpose: the adapter speaks plain S3, so MinIO, AWS S3, Ceph or Garage are a configuration
 * change, not a code change.
 * <p>
 * Every method throws {@link ObjectStorageUnavailableException} (HTTP 503) when the store cannot be
 * reached. Callers that must not fail because of the archive (the Alertmanager webhook) catch it and
 * carry on with {@code archived = false}.
 */
public interface ObjectStorage {

    /** The bucket every key of this port lives in. */
    String bucket();

    /**
     * Stores {@code content} under a <b>content-addressed</b> key
     * {@code <prefix>/<yyyy>/<MM>/<dd>/<sha256><extension>} (UTC date of "now").
     * <p>
     * The key depends only on the bytes, so a retried request writes the same object again instead
     * of creating a copy; the adapter first asks whether the object exists ({@code HEAD}) and skips
     * the upload when it does.
     *
     * @param prefix      e.g. {@code alertmanager}
     * @param extension   e.g. {@code .json}
     * @param metadata    small ASCII key/values stored with the object (source, request id); no secrets
     */
    StoredObjectRef putContentAddressed(String prefix, String extension, byte[] content, String contentType,
                                        Map<String, String> metadata);

    /** Stores (or overwrites) {@code content} under an explicit key. */
    StoredObjectRef put(String key, byte[] content, String contentType, Map<String, String> metadata);

    /** Reads an object; empty when the key does not exist. */
    Optional<StoredObject> get(String key);

    boolean exists(String key);

    /**
     * Creates the bucket when missing and (re)applies the retention lifecycle rule (03-DB §25).
     * Called at start-up; safe to call repeatedly.
     */
    void ensureBucket();

    /**
     * Cheap connectivity check used by the health indicator and {@code /api/v1/system/status}:
     * {@code HEAD bucket}. Returns normally when the store answers and the bucket exists.
     */
    void checkAvailable();

    /** Where the store lives (scheme://host:port), for diagnostics; never contains credentials. */
    String endpoint();
}
