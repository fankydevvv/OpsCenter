package com.opscenter.identity.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.identity.domain.LoginAttempt;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code login_attempts}. The count query is served by the
 * {@code (username_or_email, occurred_at DESC)} index created in V001 for exactly this purpose.
 * <p>
 * Explicit JPQL on purpose: the property is called {@code usernameOrEmail}, and Spring Data's
 * method-name parser would read a derived name such as {@code countByUsernameOrEmail...} as
 * "{@code username} OR {@code email}" - two properties that do not exist - and fail at start-up.
 */
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {

    /** Failed attempts for one login since {@code since} - the input of the rate limiter (D-09). */
    @Query("select count(a) from LoginAttempt a "
            + "where a.usernameOrEmail = :login and a.success = false and a.occurredAt > :since")
    long countFailuresSince(@Param("login") String login, @Param("since") Instant since);
}
