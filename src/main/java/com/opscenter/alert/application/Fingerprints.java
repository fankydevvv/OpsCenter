package com.opscenter.alert.application;

import java.nio.charset.StandardCharsets;

import com.opscenter.shared.application.storage.ContentHashes;

/**
 * The two identities of an alert (FR-ALT-03, FR-ALT-04, blueprint D-46).
 * <ul>
 *   <li><b>Fingerprint</b> = {@code sha256(alertName ␟ serviceCode ␟ ENV ␟ instance)}: "the same
 *       problem on the same instance". Two notifications with the same fingerprint are one alert
 *       (dedup, TC-DEDUP-001). Timestamps, annotations and extra labels ({@code job}, {@code pod})
 *       are deliberately NOT part of it - otherwise every repeat would look like a new alert.</li>
 *   <li><b>Correlation key</b> = the same without the instance: "the same problem on the same
 *       service". Alerts sharing it are grouped into one incident (TC-DEDUP-002) - it mirrors the
 *       {@code group_by: [alertname, service, environment]} of Alertmanager.</li>
 * </ul>
 * The parts are joined with U+001F (the ASCII "unit separator", ␟), a character that never occurs in
 * a label value; with a visible separator such as {@code |}, ("a|b", "c") and ("a", "b|c") would hash
 * to the same value. The inputs must already be normalised (service code lower case, environment
 * canonical upper case - {@code ServiceLookup.keyOf}), so {@code env="prod"} and
 * {@code environment="PRODUCTION"} give the same fingerprint.
 */
public final class Fingerprints {

    static final char SEPARATOR = '\u001F';

    private Fingerprints() {
    }

    public static String alert(String alertName, String serviceCode, String environment, String instance) {
        return sha256(alertName, serviceCode, environment, instance);
    }

    public static String correlation(String alertName, String serviceCode, String environment) {
        return sha256(alertName, serviceCode, environment);
    }

    private static String sha256(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                joined.append(SEPARATOR);
            }
            joined.append(parts[i] == null ? "" : parts[i].strip());
        }
        return ContentHashes.sha256Hex(joined.toString().getBytes(StandardCharsets.UTF_8));
    }
}
