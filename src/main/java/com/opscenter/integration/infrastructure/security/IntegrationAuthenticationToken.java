package com.opscenter.integration.infrastructure.security;

import java.util.List;

import com.opscenter.integration.application.IntegrationSourceRef;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * "This request was sent by integration source X" - the {@code Authentication} the webhook filter
 * stores after a successful token check (D-40). It carries the single authority
 * {@link IntegrationWebhookSecurityConfig#AUTHORITY}, never a user permission: a machine that knows
 * the Alertmanager token can post alerts and nothing else. The token itself is not kept.
 */
public class IntegrationAuthenticationToken extends AbstractAuthenticationToken {

    private final IntegrationSourceRef source;

    public IntegrationAuthenticationToken(IntegrationSourceRef source) {
        super(List.of(new SimpleGrantedAuthority(IntegrationWebhookSecurityConfig.AUTHORITY)));
        this.source = source;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public IntegrationSourceRef getPrincipal() {
        return source;
    }
}
