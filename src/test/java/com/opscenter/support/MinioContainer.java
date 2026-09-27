package com.opscenter.support;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * MinIO server for tests. The image is the same one docker-compose.yml uses: {@code minio/minio}
 * disappeared from Docker Hub, and the default image of {@code testcontainers-minio} is exactly that
 * one, so a {@link GenericContainer} with the pinned community build is used instead (R-21, D-63).
 * Credentials are test-only constants.
 */
public class MinioContainer extends GenericContainer<MinioContainer> {

    public static final String IMAGE = "pgsty/minio:RELEASE.2026-08-04T00-00-00Z";
    public static final String ACCESS_KEY = "minio-test";
    public static final String SECRET_KEY = "minio-test-secret-0123";
    public static final int API_PORT = 9000;

    public MinioContainer() {
        super(DockerImageName.parse(IMAGE));
        withEnv("MINIO_ROOT_USER", ACCESS_KEY);
        withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY);
        withCommand("server", "/data");
        withExposedPorts(API_PORT);
        waitingFor(Wait.forHttp("/minio/health/live").forPort(API_PORT).withStartupTimeout(Duration.ofSeconds(90)));
    }

    /** S3 endpoint as seen from the test JVM, e.g. {@code http://localhost:55123}. */
    public String s3Endpoint() {
        return "http://" + getHost() + ":" + getMappedPort(API_PORT);
    }
}
