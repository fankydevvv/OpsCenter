package com.opscenter.servicecatalog.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.domain.InvalidRequestException;

/**
 * Rule for health/metric/dashboard URLs of an environment (blueprint §7.1): absolute {@code http}
 * or {@code https}, at most 500 characters. The UI renders {@code dashboardUrl} as a link, so a
 * {@code javascript:} or {@code data:} URL stored here would be a stored-XSS vector - the scheme
 * whitelist closes it at the source, whatever client wrote the value.
 * <p>
 * URLs with embedded credentials ({@code http://admin:S3cret@odoo:8069/health}) are refused too: the
 * value is stored in plain text and shown to every holder of {@code service.read} (03-DB §3.6, §30 -
 * credentials are never stored in plain text). Probe credentials belong to a secret reference
 * ({@code integration_sources.secret_ref}), not to a catalog URL.
 */
public final class EndpointUrl {

    public static final int MAX_LENGTH = 500;

    private EndpointUrl() {
    }

    /**
     * @param field name reported in the error message
     * @return the trimmed URL, or {@code null} when {@code raw} is null or blank ("no URL")
     */
    public static String check(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String url = raw.trim();
        if (url.length() > MAX_LENGTH) {
            throw invalid(field, "must be at most " + MAX_LENGTH + " characters");
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw invalid(field, "must be an absolute http(s) URL");
            }
            if (uri.getRawUserInfo() != null) {
                throw invalid(field, "must not contain credentials (user:password@)");
            }
        }
        catch (URISyntaxException ex) {
            throw invalid(field, "must be an absolute http(s) URL");
        }
        return url;
    }

    private static InvalidRequestException invalid(String field, String reason) {
        return new InvalidRequestException(ErrorCodes.VALIDATION_FAILED, field + " " + reason);
    }
}
