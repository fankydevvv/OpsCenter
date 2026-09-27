package com.opscenter.integration.application;

import java.util.Optional;

/**
 * Port that turns a secret <em>reference</em> ({@code integration_sources.secret_ref}) into the
 * secret value (03-DB §3.6 "store only a credential reference", blueprint D-39).
 * <p>
 * Sprint 2 supports the {@code env:NAME} scheme (value from the environment / configuration).
 * A vault ({@code vault:path#key}) can be added later as another implementation without touching
 * the webhook code.
 */
public interface SecretResolver {

    /** @return the secret, or empty when the reference is unknown, unsupported or resolves to a blank value */
    Optional<String> resolve(String secretRef);
}
