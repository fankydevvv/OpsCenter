package com.opscenter.integration.application;

import java.util.UUID;

import com.opscenter.integration.domain.IntegrationSourceType;

/**
 * The authenticated caller of a webhook: which registered source sent the request. It is the
 * {@code principal} of the {@code IntegrationAuthenticationToken} the webhook filter puts into the
 * security context, so the controller receives it with {@code @AuthenticationPrincipal}.
 */
public record IntegrationSourceRef(UUID id, String code, UUID organizationId, IntegrationSourceType sourceType) {

    @Override
    public String toString() {
        return "IntegrationSource[" + code + "]";
    }
}
