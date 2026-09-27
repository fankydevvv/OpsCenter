package com.opscenter.integration.api;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;

import com.opscenter.integration.application.AlertmanagerWebhookService;
import com.opscenter.integration.application.IntegrationSourceRef;
import com.opscenter.integration.application.WebhookDeliverySummary;
import com.opscenter.integration.application.alertmanager.AlertmanagerWebhook;
import com.opscenter.integration.domain.PayloadTooLargeException;
import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.domain.InvalidRequestException;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/integrations/alertmanager/webhook} - Alertmanager's {@code webhook_config}
 * target (04-API §6, §23; blueprint §7.2).
 * <p>
 * By the time a request gets here, the dedicated webhook filter chain has already checked the
 * shared token and put the source into the security context ({@code @AuthenticationPrincipal}).
 * The controller rate-limits, reads the body with a hard size cap, parses/validates it and
 * delegates. It always answers {@code 200} with a summary on success - also for a replayed
 * delivery - because Alertmanager treats anything but 2xx as "retry".
 */
@RestController
public class AlertmanagerWebhookController {

    /** Optional explicit delivery id (04-API §2.5). */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    /** Alternative name used by generic webhook senders. */
    public static final String WEBHOOK_ID_HEADER = "X-Webhook-Id";

    private final AlertmanagerWebhookService webhooks;
    private final AlertmanagerPayloadReader reader;

    public AlertmanagerWebhookController(AlertmanagerWebhookService webhooks, AlertmanagerPayloadReader reader) {
        this.webhooks = webhooks;
        this.reader = reader;
    }

    @PostMapping(path = "/api/v1/integrations/alertmanager/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('INTEGRATION_WEBHOOK')")
    public WebhookDeliverySummary receive(@AuthenticationPrincipal IntegrationSourceRef source,
                                          HttpServletRequest request,
                                          @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
                                          String idempotencyKey,
                                          @RequestHeader(value = WEBHOOK_ID_HEADER, required = false)
                                          String webhookId) throws IOException {
        webhooks.admit(source);
        byte[] body = readBody(request, webhooks.maxPayloadBytes());
        AlertmanagerWebhook payload = reader.read(body);
        String deliveryKey = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : webhookId;
        return webhooks.receive(source, payload, body, deliveryKey);
    }

    /**
     * Reads at most {@code max + 1} bytes: a larger body is rejected with 413 without ever being held
     * in memory entirely (a declared {@code Content-Length} above the limit is rejected up front).
     */
    static byte[] readBody(HttpServletRequest request, int max) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > max) {
            throw new PayloadTooLargeException(max);
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(max + 1);
        }
        if (body.length > max) {
            throw new PayloadTooLargeException(max);
        }
        if (body.length == 0) {
            throw new InvalidRequestException(ErrorCodes.REQUEST_MALFORMED, "Request body is missing");
        }
        return body;
    }
}
