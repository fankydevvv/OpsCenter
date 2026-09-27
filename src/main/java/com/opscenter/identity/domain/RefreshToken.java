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
 * An opaque refresh token ({@code refresh_tokens}, 03-DB §38.1, D-02).
 * <p>
 * The client holds 32 random bytes; the database holds only their SHA-256 hash, so a database
 * leak does not hand out usable tokens. Every refresh <b>rotates</b>: the presented token is
 * revoked and {@code replaced_by_id} points to its successor. Presenting a token that already has
 * a successor therefore means two parties hold the same token - most likely theft - and the whole
 * session is revoked (reuse detection, 04-API §18 "refresh token rotate").
 * Implements {@link Persistable} for the same reason as {@code UserSession}: assigned id, insert
 * directly instead of merge.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
    }

    private RefreshToken(UUID id, UUID userId, UUID sessionId, String tokenHash, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.sessionId = sessionId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public static RefreshToken issue(UUID userId, UUID sessionId, String tokenHash, Instant expiresAt, Instant now) {
        return new RefreshToken(UUID.randomUUID(), Objects.requireNonNull(userId, "userId"),
                Objects.requireNonNull(sessionId, "sessionId"), Objects.requireNonNull(tokenHash, "tokenHash"),
                Objects.requireNonNull(expiresAt, "expiresAt"), Objects.requireNonNull(now, "now"));
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    /** Marks this token as consumed and links it to the token that replaces it. */
    public void rotateTo(RefreshToken successor, Instant now) {
        revoke(now);
        this.replacedById = successor.getId();
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

    public UUID getSessionId() {
        return sessionId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedById() {
        return replacedById;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
