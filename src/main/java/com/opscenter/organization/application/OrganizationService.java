package com.opscenter.organization.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.organization.domain.Organization;
import com.opscenter.organization.domain.OrganizationAuditActions;
import com.opscenter.organization.domain.OrganizationErrorCodes;
import com.opscenter.organization.infrastructure.OrganizationRepository;
import com.opscenter.shared.domain.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organization use cases (03-DB §6.1). The base does not create organizations through the API -
 * V005 seeds the single {@code DEFAULT} one - so this is list/get/update with optimistic locking
 * and an audit line.
 */
@Service
public class OrganizationService {

    private static final String RESOURCE_TYPE = "Organization";

    private final OrganizationRepository organizations;
    private final AuditRecorder audit;
    private final Clock clock;

    public OrganizationService(OrganizationRepository organizations, AuditRecorder audit, Clock clock) {
        this.organizations = organizations;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<OrganizationView> list() {
        return organizations.findAllByOrderByCodeAsc().stream().map(OrganizationView::from).toList();
    }

    @Transactional(readOnly = true)
    public OrganizationView get(UUID id) {
        return OrganizationView.from(load(id));
    }

    @Transactional
    public OrganizationView update(UUID id, UpdateOrganizationCommand command) {
        Organization organization = load(id);
        organization.assertVersion(command.version());
        OrganizationView before = OrganizationView.from(organization);
        if (command.name() != null) {
            organization.rename(command.name());
        }
        if (command.status() != null) {
            organization.changeStatus(command.status(), clock.instant());
        }
        organizations.saveAndFlush(organization);
        OrganizationView after = OrganizationView.from(organization);
        audit.record(OrganizationAuditActions.ORGANIZATION_UPDATED, RESOURCE_TYPE, organization.getId(), before, after);
        return after;
    }

    private Organization load(UUID id) {
        return organizations.findById(id)
                .orElseThrow(() -> new NotFoundException(OrganizationErrorCodes.ORGANIZATION_NOT_FOUND,
                        "Organization " + id + " not found"));
    }
}
