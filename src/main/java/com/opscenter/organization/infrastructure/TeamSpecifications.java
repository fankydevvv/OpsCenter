package com.opscenter.organization.infrastructure;

import java.util.UUID;

import com.opscenter.organization.domain.MasterDataStatus;
import com.opscenter.organization.domain.Team;
import com.opscenter.shared.infrastructure.persistence.LikePatterns;

import org.springframework.data.jpa.domain.Specification;

/** {@code WHERE} fragments for {@code GET /api/v1/teams} (04-API §5). */
public final class TeamSpecifications {

    private TeamSpecifications() {
    }

    public static Specification<Team> inOrganization(UUID organizationId) {
        return (root, query, cb) -> cb.equal(root.get("organizationId"), organizationId);
    }

    public static Specification<Team> hasStatus(MasterDataStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** Case-insensitive "contains" on code and name ({@code %}/{@code _} escaped). */
    public static Specification<Team> matches(String text) {
        String pattern = LikePatterns.contains(text);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("code")), pattern, LikePatterns.ESCAPE),
                cb.like(cb.lower(root.get("name")), pattern, LikePatterns.ESCAPE));
    }
}
