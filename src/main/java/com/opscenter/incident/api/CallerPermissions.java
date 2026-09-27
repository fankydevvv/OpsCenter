package com.opscenter.incident.api;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * The permission codes of the caller, read from the Spring Security {@link Authentication} (the
 * JWT's {@code permissions} claim, D-06). Controllers hand them to the application layer to compute
 * {@code allowedActions} and to decide whether alert blocks may be shown - the application layer
 * itself stays free of Spring Security types.
 */
final class CallerPermissions {

    private CallerPermissions() {
    }

    static Set<String> of(Authentication authentication) {
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }
}
