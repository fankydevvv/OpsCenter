package com.opscenter.shared.infrastructure.storage;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code opscenter.storage.*} - connection to the S3-compatible object store (blueprint D-41, §11).
 * <p>
 * Endpoint and keys come from the profile: DEV-ONLY defaults in {@code application-local.yml},
 * environment variables without defaults in {@code application-dev.yml}, a Testcontainers MinIO in
 * tests. {@link #toString()} is overridden so the secret key can never end up in a log line.
 *
 * @param enabled        {@code false} replaces the adapter by one that always reports "unavailable"
 * @param endpoint       e.g. {@code http://minio:9000}; required when enabled
 * @param accessKey      S3 access key (MinIO root user in DEV)
 * @param secretKey      S3 secret key - never logged
 * @param bucket         bucket holding the raw payloads
 * @param region         signing region; MinIO accepts any, S3 needs the real one
 * @param pathStyle      {@code http://host/bucket/key} instead of {@code http://bucket.host/key}
 * @param rawRetention       lifecycle expiry of archived raw payloads (03-DB §25: raw 90-180 days)
 * @param connectTimeout     TCP connect timeout
 * @param socketTimeout      read timeout of one HTTP call
 * @param apiCallTimeout     upper bound of one SDK call including retries
 * @param archiveTimeout     upper bound of each call of {@code putContentAddressed} (HEAD, PUT) - short,
 *                           because it runs inside a webhook that Alertmanager abandons after 10 s
 * @param rawRetentionPrefix key prefix the retention rule applies to (D-42: {@code alertmanager/})
 */
@Validated
@ConfigurationProperties(prefix = "opscenter.storage")
public record ObjectStorageProperties(
        @DefaultValue("true") boolean enabled,
        String endpoint,
        String accessKey,
        String secretKey,
        @NotBlank @DefaultValue("opscenter-raw") String bucket,
        @NotBlank @DefaultValue("us-east-1") String region,
        @DefaultValue("true") boolean pathStyle,
        @NotNull @DefaultValue("P180D") Duration rawRetention,
        @NotNull @DefaultValue("PT2S") Duration connectTimeout,
        @NotNull @DefaultValue("PT5S") Duration socketTimeout,
        @NotNull @DefaultValue("PT10S") Duration apiCallTimeout,
        @NotNull @DefaultValue("PT2S") Duration archiveTimeout,
        @NotBlank @DefaultValue("alertmanager/") String rawRetentionPrefix) {

    @Override
    public String toString() {
        return "ObjectStorageProperties[enabled=" + enabled + ", endpoint=" + endpoint + ", bucket=" + bucket
                + ", region=" + region + ", accessKey=" + (accessKey == null ? null : "***")
                + ", secretKey=" + (secretKey == null ? null : "***") + "]";
    }
}
