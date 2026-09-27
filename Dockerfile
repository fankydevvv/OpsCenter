# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# OpsCenter backend image (05-DEPLOY §7/§8).
#
# Stage 1 builds the fat jar with a JDK and the Maven wrapper; stage 2 runs it
# on a JRE only, as a non-root user. Tests are skipped here on purpose: they
# need Docker (Testcontainers) and run in CI before the image is built.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve dependencies first so this layer is cached while sources change.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package \
    && cp target/opscenter-backend-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
ENV SPRING_PROFILES_ACTIVE=dev \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
RUN groupadd --system opscenter && useradd --system --gid opscenter --home /app opscenter
WORKDIR /app
COPY --from=build --chown=opscenter:opscenter /workspace/app.jar /app/app.jar
USER opscenter
EXPOSE 8746
# Readiness carried with the image (05-DEPLOY §12 "/actuator/health UP", §18): Flyway + Hibernate
# validate run at start-up, hence the long start period. eclipse-temurin:21-jre ships curl.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD curl -fsS http://localhost:8746/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
