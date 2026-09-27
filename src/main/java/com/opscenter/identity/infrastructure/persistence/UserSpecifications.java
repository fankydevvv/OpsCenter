package com.opscenter.identity.infrastructure.persistence;

import com.opscenter.identity.domain.User;
import com.opscenter.identity.domain.UserStatus;
import com.opscenter.shared.infrastructure.persistence.LikePatterns;

import org.springframework.data.jpa.domain.Specification;

/**
 * Reusable {@code WHERE} fragments for the user list ({@code GET /api/v1/users}, 04-API §4).
 * <p>
 * Each method returns a {@link Specification}; the service combines only the ones whose filter was
 * supplied, so the SQL contains exactly the conditions the client asked for. Soft-deleted users
 * are excluded everywhere ({@link #notDeleted()}), matching 03-DB §3.3.
 */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    public static Specification<User> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<User> hasStatus(UserStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** Case-insensitive "contains" on username, email and display name ({@code %}/{@code _} escaped). */
    public static Specification<User> matches(String text) {
        String pattern = LikePatterns.contains(text);
        return (root, query, cb) -> cb.or(
                cb.like(root.get("username"), pattern, LikePatterns.ESCAPE),
                cb.like(root.get("email"), pattern, LikePatterns.ESCAPE),
                cb.like(cb.lower(root.get("displayName")), pattern, LikePatterns.ESCAPE));
    }
}
