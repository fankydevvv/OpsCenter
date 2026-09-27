package com.opscenter.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * One row per login attempt, successful or not ({@code login_attempts}, 03-DB §38.1,
 * 01-SRS FR-IAM-03 "ghi nhận login attempt").
 * <p>
 * Besides being evidence for the security team, this table is the data source of the base rate
 * limit (D-09): {@code LoginRateLimiter} counts failed rows per login in the last 15 minutes.
 * {@code user_id} is {@code null} when the login named nobody. Append-only: no mutators.
 * Implements {@link Persistable} (assigned id -> insert, never merge), like the other identity rows.
 */
@Entity
@Table(name = "login_attempts")
public class LoginAttempt implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "username_or_email", nullable = false, updatable = false, length = 255)
    private String usernameOrEmail;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "success", nullable = false, updatable = false)
    private boolean success;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", updatable = false, length = 100)
    private LoginFailureReason failureReason;

    @Column(name = "ip_address", updatable = false, length = 64)
    private String ipAddress;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected LoginAttempt() {
    }

    private LoginAttempt(String usernameOrEmail, UUID userId, boolean success, LoginFailureReason failureReason,
                         String ipAddress, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.usernameOrEmail = usernameOrEmail;
        this.userId = userId;
        this.success = success;
        this.failureReason = failureReason;
        this.ipAddress = ipAddress;
        this.occurredAt = occurredAt;
    }

    public static LoginAttempt success(String login, UUID userId, String ipAddress, Instant now) {
        return new LoginAttempt(login, userId, true, null, ipAddress, now);
    }

    public static LoginAttempt failure(String login, UUID userId, LoginFailureReason reason, String ipAddress,
                                       Instant now) {
        return new LoginAttempt(login, userId, false, reason, ipAddress, now);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getUsernameOrEmail() {
        return usernameOrEmail;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isSuccess() {
        return success;
    }

    public LoginFailureReason getFailureReason() {
        return failureReason;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
