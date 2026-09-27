package com.opscenter.alert.application;

/**
 * Where the verbatim webhook of the alert's latest notification is archived (D-42). {@code archived
 * = false} (and the other fields {@code null}) when object storage was unavailable at that moment -
 * the alert was still ingested.
 */
public record RawArchiveInfo(String bucket, String key, String sha256, Long sizeBytes, boolean archived) {

    public static RawArchiveInfo notArchived() {
        return new RawArchiveInfo(null, null, null, null, false);
    }
}
