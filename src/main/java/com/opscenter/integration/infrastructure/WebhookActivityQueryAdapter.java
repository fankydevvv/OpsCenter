package com.opscenter.integration.infrastructure;

import java.time.Instant;
import java.util.Optional;

import com.opscenter.integration.domain.IntegrationSource;
import com.opscenter.shared.application.status.WebhookActivityQuery;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the shared kernel's optional {@link WebhookActivityQuery} port so the Alertmanager card
 * of {@code /admin/system} can show {@code lastWebhookAt} (D-63) - "Alertmanager is UP but the last
 * webhook is hours old" is the classic sign of a broken route or token.
 */
@Component
public class WebhookActivityQueryAdapter implements WebhookActivityQuery {

    private final IntegrationSourceRepository sources;

    public WebhookActivityQueryAdapter(IntegrationSourceRepository sources) {
        this.sources = sources;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> lastEventAt(String sourceCode) {
        return sources.findByCode(sourceCode).map(IntegrationSource::getLastEventAt);
    }
}
