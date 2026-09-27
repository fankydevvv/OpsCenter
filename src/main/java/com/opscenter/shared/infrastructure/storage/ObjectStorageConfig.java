package com.opscenter.shared.infrastructure.storage;

import java.net.URI;
import java.time.Clock;

import com.opscenter.shared.application.storage.ObjectStorage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.LegacyMd5Plugin;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Wires the {@link ObjectStorage} port (blueprint D-41).
 * <p>
 * The {@link S3Client} is configured for S3-<em>compatible</em> stores rather than AWS itself:
 * <ul>
 *   <li>{@code endpointOverride} + {@code forcePathStyle}: talk to {@code http://minio:9000/<bucket>}.</li>
 *   <li>Explicit region and static credentials: the SDK must not go looking for {@code ~/.aws} files
 *       or an EC2 metadata service inside a container.</li>
 *   <li>Checksums {@code WHEN_REQUIRED} + {@link LegacyMd5Plugin}: recent SDK versions add CRC
 *       checksum headers to every request by default, which not every S3-compatible server accepts;
 *       this restores the classic behaviour (Content-MD5 where the API requires it) - R-31.</li>
 *   <li>{@link UrlConnectionHttpClient}: the JDK HTTP stack, no Netty/Apache on the classpath;
 *       short connect/read timeouts and an overall call timeout so a hung store cannot stall a
 *       request for minutes.</li>
 * </ul>
 * {@code opscenter.storage.enabled=false} swaps in {@link DisabledObjectStorage}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class ObjectStorageConfig {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "opscenter.storage", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class S3StorageConfig {

        @Bean(destroyMethod = "close")
        S3Client objectStorageS3Client(ObjectStorageProperties properties) {
            if (properties.endpoint() == null || properties.endpoint().isBlank()) {
                throw new IllegalStateException("opscenter.storage.endpoint is required when opscenter.storage.enabled=true "
                        + "(set OBJECT_STORAGE_ENDPOINT, e.g. http://minio:9000, or disable object storage)");
            }
            return S3Client.builder()
                    .endpointOverride(URI.create(properties.endpoint()))
                    .forcePathStyle(properties.pathStyle())
                    .region(Region.of(properties.region()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .addPlugin(LegacyMd5Plugin.create())
                    .httpClientBuilder(UrlConnectionHttpClient.builder()
                            .connectionTimeout(properties.connectTimeout())
                            .socketTimeout(properties.socketTimeout()))
                    .overrideConfiguration(override -> override.apiCallTimeout(properties.apiCallTimeout()))
                    .build();
        }

        @Bean
        ObjectStorage objectStorage(S3Client objectStorageS3Client, ObjectStorageProperties properties, Clock clock) {
            return new S3ObjectStorage(objectStorageS3Client, properties, clock);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "opscenter.storage", name = "enabled", havingValue = "false")
    static class DisabledStorageConfig {

        @Bean
        ObjectStorage objectStorage(ObjectStorageProperties properties) {
            return new DisabledObjectStorage(properties.bucket());
        }
    }
}
