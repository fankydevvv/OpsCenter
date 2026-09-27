package com.opscenter.servicecatalog.infrastructure;

import java.util.UUID;

import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import com.opscenter.servicecatalog.domain.CatalogService;
import com.opscenter.servicecatalog.domain.ServiceEnvironment;
import com.opscenter.servicecatalog.domain.ServiceStatus;
import com.opscenter.shared.infrastructure.persistence.LikePatterns;

import org.springframework.data.jpa.domain.Specification;

/** {@code WHERE} fragments of {@code GET /api/v1/services} (04-API §5, blueprint §7.1). */
public final class ServiceSpecifications {

    private ServiceSpecifications() {
    }

    public static Specification<CatalogService> isActive(boolean active) {
        return (root, query, cb) -> cb.equal(root.get("active"), active);
    }

    public static Specification<CatalogService> hasStatus(ServiceStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<CatalogService> ownedByTeam(UUID teamId) {
        return (root, query, cb) -> cb.equal(root.get("owningTeamId"), teamId);
    }

    /** Case-insensitive "contains" on code and name ({@code %}/{@code _} escaped). */
    public static Specification<CatalogService> matches(String text) {
        String pattern = LikePatterns.contains(text);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("code")), pattern, LikePatterns.ESCAPE),
                cb.like(cb.lower(root.get("name")), pattern, LikePatterns.ESCAPE));
    }

    /**
     * Services that have an ACTIVE environment with the given canonical code - a correlated
     * {@code EXISTS} sub-query, because environments are a separate aggregate (no JPA relation).
     */
    public static Specification<CatalogService> hasEnvironment(String environmentCode) {
        return (root, query, cb) -> {
            Subquery<UUID> sub = query.subquery(UUID.class);
            Root<ServiceEnvironment> environment = sub.from(ServiceEnvironment.class);
            sub.select(environment.get("serviceId")).where(
                    cb.equal(environment.get("serviceId"), root.get("id")),
                    cb.equal(environment.get("environmentCode"), environmentCode),
                    cb.isTrue(environment.get("active")));
            return cb.exists(sub);
        };
    }
}
