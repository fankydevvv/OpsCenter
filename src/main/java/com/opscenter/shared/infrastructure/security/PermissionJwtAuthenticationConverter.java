package com.opscenter.shared.infrastructure.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns a validated JWT into the {@code Authentication} Spring Security authorises against (D-06).
 * <p>
 * Authorities are the {@code permissions} claim <b>verbatim</b> ({@code user.read}) so controllers
 * write {@code @PreAuthorize("hasAuthority('user.read')")} with the same code that is stored in the
 * {@code permissions} table (04-API §4) - plus {@code ROLE_<code>} for each role, so
 * {@code hasRole('ADMIN')} also works when a coarse check is enough. The default
 * {@code JwtGrantedAuthoritiesConverter} would prefix everything with {@code SCOPE_}, which is
 * why a custom converter exists.
 */
@Component
public class PermissionJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String ROLE_PREFIX = "ROLE_";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        for (String permission : stringList(jwt, JwtClaims.PERMISSIONS)) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }
        for (String role : stringList(jwt, JwtClaims.ROLES)) {
            authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role));
        }
        String name = jwt.getClaimAsString(JwtClaims.USERNAME);
        return new JwtAuthenticationToken(jwt, authorities, name != null ? name : jwt.getSubject());
    }

    private static List<String> stringList(Jwt jwt, String claim) {
        List<String> values = jwt.getClaimAsStringList(claim);
        return values == null ? List.of() : values;
    }
}
