package com.opscenter.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * One login session ({@code user_sessions}, 03-DB §38.1, D-03).
 * <p>
 * The session id travels in the JWT {@code sid} claim and is checked on every request by
 * {@code SessionActiveJwtValidator}. That is what makes logout and account lock take effect
 * immediately even though the access token itself is still cryptographically valid
 * (TC-AUTH-005). {@code session_key_hash} is a random secret hashed with SHA-256; it is never
 * handed out, it only makes the row unique and unguessable.
 * <p>
 * Not an {@code AuditableEntity}: a session is created by the user who logs in (there is no JWT
 * yet) and is never edited by an administrator, so audit columns and a version would be noise.
 * It still implements {@link Persistable} so Spring Data inserts it directly instead of merging
 * (see {@code AuditableEntity} for why an assigned id needs this hint).
 */
@Entity
@Table(name = "user_sessions")
public class UserSession implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "session_key_hash", nullable = false, updatable = false, length = 255)
    private String sessionKeyHash;

    @Column(name = "ip_address", updatable = false, length = 64)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserSession() {
    }

    private UserSession(UUID id, UUID userId, String sessionKeyHash, String ipAddress, String userAgent,
                        Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.sessionKeyHash = sessionKeyHash;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public static UserSession open(UUID userId, String sessionKeyHash, String ipAddress, String userAgent,
                                   Instant expiresAt, Instant now) {
        return new UserSession(UUID.randomUUID(), Objects.requireNonNull(userId, "userId"),
                Objects.requireNonNull(sessionKeyHash, "sessionKeyHash"), truncate(ipAddress, 64),
                userAgent, Objects.requireNonNull(expiresAt, "expiresAt"), Objects.requireNonNull(now, "now"));
    }

    /** A session is usable while it is neither revoked nor past its expiry. */
    public boolean isActiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    /** Sliding expiry: each successful refresh keeps the session alive for another refresh TTL. */
    public void extendTo(Instant newExpiry) {
        if (newExpiry.isAfter(expiresAt)) {
            this.expiresAt = newExpiry;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
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

    public UUID getUserId() {
        return userId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
