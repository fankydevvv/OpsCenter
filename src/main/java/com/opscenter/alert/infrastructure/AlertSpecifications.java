package com.opscenter.alert.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import com.opscenter.alert.domain.Alert;
import com.opscenter.alert.domain.AlertSourceType;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.shared.domain.Severity;
import com.opscenter.shared.infrastructure.persistence.LikePatterns;

import org.springframework.data.jpa.domain.Specification;

/** {@code WHERE} fragments of {@code GET /api/v1/alerts} (04-API §6, blueprint §7.3). */
public final class AlertSpecifications {

    private AlertSpecifications() {
    }

    public static Specification<Alert> statusIn(Collection<AlertStatus> statuses) {
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    public static Specification<Alert> severityIn(Collection<Severity> severities) {
        return (root, query, cb) -> root.get("severity").in(severities);
    }

    public static Specification<Alert> service(UUID serviceId) {
        return (root, query, cb) -> cb.equal(root.get("serviceId"), serviceId);
    }

    public static Specification<Alert> environment(String environment) {
        return (root, query, cb) -> cb.equal(root.get("environment"), environment);
    }

    /** {@code mapped=false} is the UNMAPPED work queue (D-48, partial index {@code idx_alerts_unmapped}). */
    public static Specification<Alert> mapped(boolean mapped) {
        return (root, query, cb) -> mapped ? cb.isNotNull(root.get("serviceId")) : cb.isNull(root.get("serviceId"));
    }

    public static Specification<Alert> sourceType(AlertSourceType sourceType) {
        return (root, query, cb) -> cb.equal(root.get("sourceType"), sourceType);
    }

    /** Case-insensitive "contains" on alert name, instance and reported service code. */
    public static Specification<Alert> matches(String text) {
        String pattern = LikePatterns.contains(text);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("alertName")), pattern, LikePatterns.ESCAPE),
                cb.like(cb.lower(cb.coalesce(root.<String>get("instance"), "")), pattern, LikePatterns.ESCAPE),
                cb.like(cb.lower(cb.coalesce(root.<String>get("serviceCode"), "")), pattern, LikePatterns.ESCAPE));
    }

    public static Specification<Alert> lastSeenFrom(Instant from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("lastSeenAt"), from);
    }

    public static Specification<Alert> lastSeenTo(Instant to) {
        return (root, query, cb) -> cb.lessThan(root.get("lastSeenAt"), to);
    }

    public static Specification<Alert> idIn(Collection<UUID> ids) {
        return (root, query, cb) -> root.get("id").in(ids);
    }
}
