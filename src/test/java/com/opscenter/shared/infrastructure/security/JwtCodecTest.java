package com.opscenter.shared.infrastructure.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.opscenter.support.TestJwts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-01: HS256 round trip with the claim layout every module relies on; a different secret must fail. */
class JwtCodecTest {

    private static final JwtProperties PROPS = new JwtProperties("unit-test-secret-0123456789abcdefghij",
            Duration.ofMinutes(30), Duration.ofDays(7), "opscenter");

    private final JwtConfig config = new JwtConfig();

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OAuth2TokenValidator<Jwt>> noExtraValidators() {
        return (ObjectProvider<OAuth2TokenValidator<Jwt>>) (ObjectProvider<?>) new StaticListableBeanFactory()
                .getBeanProvider(ResolvableType.forClassWithGenerics(OAuth2TokenValidator.class, Jwt.class));
    }

    @Test
    void encodeThenDecode_preservesClaims() {
        SecretKey key = config.jwtSecretKey(PROPS);
        JwtEncoder encoder = config.jwtEncoder(key);
        JwtDecoder decoder = config.jwtDecoder(key, PROPS, noExtraValidators());
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        String token = TestJwts.issue(encoder, userId, sessionId, "coordinator", List.of("COORDINATOR"),
                List.of("team.read", "team.create"), Instant.now(), PROPS.accessTtl());
        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString(JwtClaims.SESSION_ID)).isEqualTo(sessionId.toString());
        assertThat(jwt.getClaimAsString(JwtClaims.USERNAME)).isEqualTo("coordinator");
        assertThat(jwt.getClaimAsStringList(JwtClaims.ROLES)).containsExactly("COORDINATOR");
        assertThat(jwt.getClaimAsStringList(JwtClaims.PERMISSIONS)).containsExactly("team.read", "team.create");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("opscenter");
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(29)));
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
    }

    @Test
    void tokenSignedWithAnotherSecret_isRejected() {
        JwtEncoder otherEncoder = config.jwtEncoder(config.jwtSecretKey(
                new JwtProperties("another-secret-that-is-long-enough-123456", Duration.ofMinutes(30),
                        Duration.ofDays(7), "opscenter")));
        JwtDecoder decoder = config.jwtDecoder(config.jwtSecretKey(PROPS), PROPS, noExtraValidators());
        String token = TestJwts.issue(otherEncoder, UUID.randomUUID(), UUID.randomUUID(), "x", List.of(), List.of(),
                Instant.now(), Duration.ofMinutes(5));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void wrongIssuer_isRejected() {
        SecretKey key = config.jwtSecretKey(PROPS);
        JwtDecoder decoder = config.jwtDecoder(key, PROPS, noExtraValidators());
        String token = TestJwts.issue(config.jwtEncoder(key), "evil", UUID.randomUUID(), UUID.randomUUID(), "x",
                List.of(), List.of(), Instant.now(), Duration.ofMinutes(5));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class)
                .hasMessageContaining("iss");
    }
}
