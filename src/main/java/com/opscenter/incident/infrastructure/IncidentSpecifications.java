package com.opscenter.incident.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import com.opscenter.incident.domain.Incident;
import com.opscenter.incident.domain.IncidentSource;
import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.shared.domain.Severity;
import com.opscenter.shared.infrastructure.persistence.LikePatterns;

import org.springframework.data.jpa.domain.Specification;

/** {@code WHERE} fragments of {@code GET /api/v1/incidents} (04-API §7, blueprint §7.4). */
public final class IncidentSpecifications {

    private IncidentSpecifications() {
    }

    public static Specification<Incident> statusIn(Collection<IncidentStatus> statuses) {
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    public static Specification<Incident> severityIn(Collection<Severity> severities) {
        return (root, query, cb) -> root.get("severity").in(severities);
    }

    /** {@code open=true}: not RESOLVED/VERIFIED/CLOSED; {@code open=false}: only those. */
    public static Specification<Incident> open(boolean open) {
        return (root, query, cb) -> open
                ? cb.not(root.get("status").in(IncidentStatus.NOT_OPEN))
                : root.get("status").in(IncidentStatus.NOT_OPEN);
    }

    public static Specification<Incident> service(UUID serviceId) {
        return (root, query, cb) -> cb.equal(root.get("serviceId"), serviceId);
    }

    /** The fallback queue of D-49: incidents whose alert matched no catalog service. */
    public static Specification<Incident> unmapped(boolean unmapped) {
        return (root, query, cb) -> unmapped ? cb.isNull(root.get("serviceId")) : cb.isNotNull(root.get("serviceId"));
    }

    public static Specification<Incident> environment(String environment) {
        return (root, query, cb) -> cb.equal(root.get("environment"), environment);
    }

    public static Specification<Incident> owningTeam(UUID teamId) {
        return (root, query, cb) -> cb.equal(root.get("owningTeamId"), teamId);
    }

    public static Specification<Incident> assignee(UUID userId) {
        return (root, query, cb) -> cb.equal(root.get("assigneeId"), userId);
    }

    public static Specification<Incident> source(IncidentSource source) {
        return (root, query, cb) -> cb.equal(root.get("source"), source);
    }

    /** Case-insensitive "contains" on incident number and title ({@code %}/{@code _} escaped). */
    public static Specification<Incident> matches(String text) {
        String pattern = LikePatterns.contains(text);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("incidentNo")), pattern, LikePatterns.ESCAPE),
                cb.like(cb.lower(root.get("title")), pattern, LikePatterns.ESCAPE));
    }

    public static Specification<Incident> createdFrom(Instant from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    public static Specification<Incident> createdTo(Instant to) {
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), to);
    }
}
