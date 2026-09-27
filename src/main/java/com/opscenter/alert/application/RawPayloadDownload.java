package com.opscenter.alert.application;

/**
 * The archived webhook body, read back from object storage for {@code GET /alerts/{id}/raw}.
 *
 * @param fileName suggested download name
 */
public record RawPayloadDownload(String fileName, String contentType, byte[] content) {
}
