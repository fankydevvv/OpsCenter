package com.opscenter.shared.application.storage;

/**
 * What the database keeps about a blob in object storage (03-DB §3.5): enough to find it again and
 * to prove its integrity, never the bytes themselves.
 *
 * @param bucket    bucket name
 * @param key       object key inside the bucket
 * @param sha256    lower-case hex SHA-256 of the content
 * @param sizeBytes content length
 */
public record StoredObjectRef(String bucket, String key, String sha256, long sizeBytes) {
}
