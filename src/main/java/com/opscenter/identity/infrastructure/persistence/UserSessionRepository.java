package com.opscenter.identity.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.identity.domain.UserSession;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code user_sessions}.
 * <p>
 * {@link #revokeAllActiveByUser} is a bulk JPQL update: locking a user must kill every session in
 * one statement rather than loading and saving each row (D-03, D-07). {@code flushAutomatically}
 * writes pending entity changes first so the statement sees them; the persistence context is
 * deliberately <em>not</em> cleared afterwards - the callers still hold the {@code User} aggregate
 * they are editing, and clearing would silently detach it.
 */
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    @Modifying(flushAutomatically = true)
    @Query("update UserSession s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAllActiveByUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
