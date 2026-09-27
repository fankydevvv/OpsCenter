package com.opscenter.identity.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.opscenter.identity.domain.Permission;
import com.opscenter.identity.domain.Role;
import com.opscenter.identity.domain.User;
import com.opscenter.shared.infrastructure.security.JwtClaims;
import com.opscenter.shared.infrastructure.security.JwtProperties;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-01: the issued JWT carries exactly the claim layout the shared converter and the
 * {@code SessionActiveJwtValidator} rely on, with a 1800 s lifetime; D-02: refresh tokens are
 * random and only their SHA-256 is ever persisted.
 */
class JwtTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final String SECRET = "test-only-secret-not-for-real-use-0123456789";

    private final SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private final JwtProperties properties = new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(7),
            "opscenter");
    private final JwtTokenService service = new JwtTokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key)),
            properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issueAccessToken_carriesSubjectSessionRolesAndPermissions() {
        Role role = Role.create("ENGINEER", "Engineer", null);
        role.replacePermissions(Set.of(new Permission(UUID.randomUUID(), "team.read", "team", "read"),
                new Permission(UUID.randomUUID(), "organization.read", "organization", "read")));
        User user = User.register("engineer.a", "engineer.a@opscenter.local", "{bcrypt}x", "Engineer A");
        user.replaceRoles(Set.of(role));
        UUID sessionId = UUID.randomUUID();

        AccessToken token = service.issueAccessToken(user, sessionId);

        assertThat(token.expiresInSeconds()).isEqualTo(1800);
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        // Validate exp/nbf against the same fixed clock that issued the token; the default
        // validator uses the wall clock and would reject NOW + 30 min once that time has passed.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        decoder.setJwtValidator(timestamps);
        Jwt jwt = decoder.decode(token.value());
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        // getIssuer() would try to parse "opscenter" as a URL; the raw claim is what the validator compares
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("opscenter");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(jwt.getClaimAsString(JwtClaims.SESSION_ID)).isEqualTo(sessionId.toString());
        assertThat(jwt.getClaimAsString(JwtClaims.USERNAME)).isEqualTo("engineer.a");
        assertThat(jwt.getClaimAsStringList(JwtClaims.ROLES)).containsExactly("ENGINEER");
        assertThat(jwt.getClaimAsStringList(JwtClaims.PERMISSIONS)).containsExactly("organization.read", "team.read");
        assertThat(jwt.getId()).isNotBlank();
    }

    @Test
    void opaqueTokens_areRandom_urlSafe_andHashedDeterministically() {
        String first = service.newOpaqueToken();
        String second = service.newOpaqueToken();

        assertThat(first).isNotEqualTo(second).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(service.hash(first)).isEqualTo(service.hash(first)).hasSize(64).matches("[0-9a-f]+");
        assertThat(service.hash(first)).isNotEqualTo(service.hash(second));
        assertThat(service.refreshTtl()).isEqualTo(Duration.ofDays(7));
    }
}
