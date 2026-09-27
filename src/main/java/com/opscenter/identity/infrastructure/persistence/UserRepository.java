package com.opscenter.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import com.opscenter.identity.domain.User;
import com.opscenter.identity.domain.UserStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code users}.
 * <p>
 * {@link JpaSpecificationExecutor} backs the paginated search with optional filters
 * ({@code q}, {@code status}); building the {@code WHERE} clause dynamically with
 * {@link UserSpecifications} avoids the "{@code :param is null or ...}" JPQL trick, which
 * PostgreSQL cannot type for enum parameters.
 */
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    /**
     * FR-IAM-01: a user logs in with username <em>or</em> email. Both columns are stored lower
     * case, so the caller passes the normalised login (see {@code User.normalize}).
     */
    @Query("select u from User u where u.username = :login or u.email = :login")
    Optional<User> findByLogin(@Param("login") String normalizedLogin);

    boolean existsByUsername(String normalizedUsername);

    boolean existsByEmail(String normalizedEmail);

    /** Non-deleted users with the given status holding the role (bootstrap administrator, D-27). */
    @Query("select count(u) from User u join u.roles r "
            + "where r.code = :roleCode and u.status = :status and u.deletedAt is null")
    long countWithRoleAndStatus(@Param("roleCode") String roleCode, @Param("status") UserStatus status);
}
