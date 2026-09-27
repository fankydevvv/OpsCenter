/**
 * Alert module (02-SAD §6.4, 01-SRS §5 FR-ALT-01..05; Sprint 2, blueprint D-42..D-51, D-59).
 * <p>
 * Receives canonical alert events from the integration module, deduplicates them by fingerprint
 * (one row per firing episode), resolves the service through the catalog's {@code ServiceLookup},
 * and asks the incident module to group new alerts into incidents. Concurrency: a distributed lock
 * per correlation group (Redis, PostgreSQL fallback) plus partial unique indexes as the final guard.
 * <p>
 * Dependencies: {@code alert -> incident, servicecatalog, shared, audit}. The incident module reads
 * alerts back only through ports it declares itself ({@code LinkedAlertReader}, {@code AlertStatsReader}),
 * implemented here in {@code infrastructure} - so there is no dependency cycle.
 */
package com.opscenter.alert;
