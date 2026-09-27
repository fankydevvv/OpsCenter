package com.opscenter.integration.infrastructure;

import java.util.Map;
import java.util.Optional;

import com.opscenter.integration.application.IntegrationProperties;
import com.opscenter.integration.application.SecretResolver;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * {@link SecretResolver} for the {@code env:NAME} scheme (blueprint D-39).
 * <p>
 * Resolution order for {@code env:NAME}:
 * <ol>
 *   <li>a known secret bound by this application's configuration - {@code OPSCENTER_ALERTMANAGER_TOKEN}
 *       is bound to {@code opscenter.integration.alertmanager.token} in {@code application.yml}, which
 *       lets a profile (e.g. {@code test}) override it under a namespaced key;</li>
 *   <li>otherwise Spring's {@link Environment}: OS environment variables, system properties and the
 *       {@code ../.env} file imported by the {@code local} profile.</li>
 * </ol>
 * The value is only ever returned to the authenticator; it is never logged.
 */
@Component
public class EnvSecretResolver implements SecretResolver {

    static final String ENV_SCHEME = "env:";

    private final Environment environment;
    private final Map<String, String> boundSecrets;

    public EnvSecretResolver(Environment environment, IntegrationProperties properties) {
        this.environment = environment;
        String alertmanagerToken = properties.alertmanager().token();
        this.boundSecrets = alertmanagerToken == null || alertmanagerToken.isBlank() ? Map.of()
                : Map.of("OPSCENTER_ALERTMANAGER_TOKEN", alertmanagerToken);
    }

    @Override
    public Optional<String> resolve(String secretRef) {
        if (secretRef == null || !secretRef.startsWith(ENV_SCHEME)) {
            return Optional.empty();
        }
        String name = secretRef.substring(ENV_SCHEME.length()).trim();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        String value = boundSecrets.get(name);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(name);
        }
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
