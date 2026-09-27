package com.opscenter.identity.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.opscenter.identity.domain.User;
import com.opscenter.shared.infrastructure.security.JwtClaims;
import com.opscenter.shared.infrastructure.security.JwtProperties;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues access tokens and generates/hashes opaque refresh tokens (D-01, D-02).
 * <p>
 * The access token is a signed HS256 JWT built with the shared {@link JwtEncoder}; its claims are
 * exactly what the shared {@code PermissionJwtAuthenticationConverter} reads back:
 * {@code sub} (user id), {@code sid} (session id), {@code username}, {@code roles[]},
 * {@code permissions[]}. Because permissions are embedded, a role change only takes effect at the
 * next refresh or login (D-07) - the price paid for not hitting the database for authorisation.
 * <p>
 * Refresh tokens are <em>not</em> JWTs: 32 random bytes, base64url encoded, stored only as a
 * SHA-256 hex hash so the database never contains a usable secret.
 */
@Component
public class JwtTokenService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public JwtTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /** Signs an access token for {@code user} bound to the login session {@code sessionId}. */
    public AccessToken issueAccessToken(User user, UUID sessionId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(JwtClaims.SESSION_ID, sessionId.toString())
                .claim(JwtClaims.USERNAME, user.getUsername())
                .claim(JwtClaims.ROLES, user.roleCodes())
                .claim(JwtClaims.PERMISSIONS, user.permissionCodes())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, properties.accessTtl().toSeconds(), expiresAt);
    }

    /** Lifetime of a refresh token and of the session it keeps alive. */
    public Duration refreshTtl() {
        return properties.refreshTtl();
    }

    /** A new random opaque token (URL-safe, no padding) to hand to the client once. */
    public String newOpaqueToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex of an opaque token - the only form that is persisted. */
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
