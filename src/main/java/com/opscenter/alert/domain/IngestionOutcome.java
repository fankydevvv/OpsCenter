package com.opscenter.alert.domain;

/**
 * What the ingestion decided for one alert of a delivery (blueprint §8.3 decision table):
 *
 * <pre>
 *  notification  FIRING alert with same fingerprint?  action                               outcome
 *  firing        yes                                  count + 1, last_seen                 DEDUPLICATED
 *  firing        no, but an older RESOLVED one         new alert row, correlate              REFIRED
 *  firing        no                                   new alert row, correlate              CREATED
 *  resolved      yes                                  status RESOLVED, timeline of incident RESOLVED
 *  resolved      no, but a RESOLVED one exists         repeated resolved notice: log only    DEDUPLICATED
 *  resolved      never seen                           new RESOLVED row, no incident         RESOLVED_UNKNOWN
 * </pre>
 * The "repeated resolved notice" row matters in practice: Alertmanager keeps a resolved alert in its
 * group for a while and re-sends it with the next notification of that group; it must not create a
 * new alert every time.
 */
public enum IngestionOutcome {
    CREATED,
    DEDUPLICATED,
    REFIRED,
    RESOLVED,
    RESOLVED_UNKNOWN
}
