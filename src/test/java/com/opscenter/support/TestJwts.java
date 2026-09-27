package com.opscenter.support;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.shared.infrastructure.security.JwtClaims;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Mints access tokens with the application's own {@code JwtEncoder} so tests exercise the real
 * decoder, validators and authority conversion instead of a mocked {@code Authentication}.
 * The identity module's {@code TokenService} produces the same claim layout (D-01).
 */
public final class TestJwts {

    public static final String ISSUER = "opscenter";
    /** The 15 codes of blueprint §6.2 plus {@code user.delete} (V006, D-26). */
    public static final List<String> ALL_BASE_PERMISSIONS = List.of(
            "user.read", "user.create", "user.update", "user.lock", "user.delete", "user.role.assign",
            "role.read", "role.create", "role.permission.update", "permission.read",
            "organization.read", "organization.update",
            "team.read", "team.create", "team.update", "team.member.manage");

    private TestJwts() {
    }

    public static String admin(JwtEncoder encoder, UUID userId, UUID sessionId) {
        return issue(encoder, userId, sessionId, "admin", List.of("ADMIN"), ALL_BASE_PERMISSIONS,
                Instant.now(), Duration.ofMinutes(30));
    }

    public static String engineer(JwtEncoder encoder, UUID userId, UUID sessionId) {
        return issue(encoder, userId, sessionId, "engineer.a", List.of("ENGINEER"),
                List.of("organization.read", "team.read"), Instant.now(), Duration.ofMinutes(30));
    }

    public static String issue(JwtEncoder encoder, UUID userId, UUID sessionId, String username,
                               List<String> roles, List<String> permissions, Instant issuedAt, Duration ttl) {
        return issue(encoder, ISSUER, userId, sessionId, username, roles, permissions, issuedAt, ttl);
    }

    public static String issue(JwtEncoder encoder, String issuer, UUID userId, UUID sessionId, String username,
                               List<String> roles, List<String> permissions, Instant issuedAt, Duration ttl) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .claim(JwtClaims.SESSION_ID, sessionId.toString())
                .claim(JwtClaims.USERNAME, username)
                .claim(JwtClaims.ROLES, roles)
                .claim(JwtClaims.PERMISSIONS, permissions)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
