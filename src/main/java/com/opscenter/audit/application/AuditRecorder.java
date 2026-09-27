package com.opscenter.audit.application;

import java.time.Clock;
import java.util.UUID;

import com.opscenter.audit.domain.AuditLog;
import com.opscenter.audit.domain.SensitiveDataGuard;
import com.opscenter.audit.infrastructure.AuditLogRepository;
import com.opscenter.shared.application.CurrentUser;
import com.opscenter.shared.application.RequestContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one entry point business modules use to write audit records (D-15).
 * <p>
 * {@code Propagation.MANDATORY}: an audit line is only meaningful together with the change it
 * describes, so it must be written in the same transaction (03-DB §27) - calling it outside one is
 * a programming error and fails immediately. Actor, request id and source IP are resolved here
 * from the {@link CurrentUser}/{@link RequestContext} ports, so services only pass what they know:
 * the action, the resource and DTO snapshots before/after.
 */
@Service
public class AuditRecorder {

    private final AuditLogRepository repository;
    private final CurrentUser currentUser;
    private final RequestContext requestContext;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public AuditRecorder(AuditLogRepository repository, CurrentUser currentUser, RequestContext requestContext,
                         JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.requestContext = requestContext;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** Records an action performed by the authenticated caller. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditLog record(String action, String resourceType, UUID resourceId, Object before, Object after) {
        return record(action, resourceType, resourceId, before, after, currentUser.actorId().orElse(null), null);
    }

    /**
     * Records an action with an explicit actor - needed where no JWT exists yet (login success is
     * performed by the user who is logging in; a failed login of an unknown user has no actor).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditLog record(String action, String resourceType, UUID resourceId, Object before, Object after,
                           UUID actorId, UUID organizationId) {
        String beforeJson = snapshot(before, "before");
        String afterJson = snapshot(after, "after");
        AuditLog entry = new AuditLog(UUID.randomUUID(), organizationId, actorId, action, resourceType, resourceId,
                beforeJson, afterJson, requestContext.requestId().orElse(null),
                requestContext.clientIp().orElse(null), clock.instant());
        return repository.save(entry);
    }

    private String snapshot(Object value, String which) {
        if (value == null) {
            return null;
        }
        JsonNode tree = jsonMapper.valueToTree(value);
        SensitiveDataGuard.assertNoSensitiveData(tree, which);
        return jsonMapper.writeValueAsString(tree);
    }
}
