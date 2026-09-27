/**
 * Incident module (02-SAD §6.5, §9; 01-SRS §6-§7; Sprint 2, blueprint D-54..D-61).
 * <p>
 * The incident aggregate with its state machine (ACK, start investigation, mitigate, resolve; close
 * guarded until recovery verification exists), status history, timeline, the alert links, grouping
 * of alerts into incidents ({@code IncidentCorrelationService}) and the Operations Center summary.
 * Every change writes state + history + timeline + audit + outbox event in one transaction.
 * <p>
 * Dependencies: {@code incident -> servicecatalog, organization, identity (lookup ports), shared,
 * audit}. It never imports the alert module; alert data arrives through the ports
 * {@code LinkedAlertReader} and {@code AlertStatsReader} that the alert module implements.
 */
package com.opscenter.incident;
