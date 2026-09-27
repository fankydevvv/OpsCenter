package com.opscenter.identity.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import com.opscenter.identity.domain.RefreshToken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code refresh_tokens}. Lookup is always by hash - the raw token never
 * reaches the database (D-02). Bulk revocations mirror {@code UserSessionRepository}.
 * <p>
 * {@link #findByTokenHash} takes a row lock ({@code SELECT ... FOR UPDATE}) so that two
 * concurrent refreshes with the same token cannot both read "not revoked": the second one waits
 * for the first to commit and then sees the rotated row (reuse detection, D-02/D-29). It must be
 * called inside a read-write transaction.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.sessionId = :sessionId and t.revokedAt is null")
    int revokeAllActiveBySession(@Param("sessionId") UUID sessionId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllActiveByUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
