/**
 * Integration Hub module (01-SRS §14, 03-DB §40.1, 04-API §6, §23; Sprint 2, blueprint D-38..D-44, D-53).
 * <p>
 * Sprint 2 content: the registry of inbound sources ({@code integration_sources}), the Alertmanager
 * webhook with its own security filter chain (shared bearer token compared in constant time, no JWT),
 * rate limits, raw-payload archiving to object storage, webhook idempotency and the Alertmanager
 * adapter that maps the v4 payload to canonical alert events.
 * <p>
 * Dependencies: {@code integration -> alert, servicecatalog (label normalisation), shared, audit}.
 */
package com.opscenter.integration;
