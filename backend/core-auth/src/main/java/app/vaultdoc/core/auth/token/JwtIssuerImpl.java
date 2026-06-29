package app.vaultdoc.core.auth.token;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

public class JwtIssuerImpl implements JwtIssuer {

    private static final String ISSUER = "vaultdoc";
    private static final String CLAIM_EMAIL = "email";

    private final SecretKey signingKey;
    private final Duration accessTtl;
    private final Clock clock;

    public JwtIssuerImpl(String secret, Duration accessTtl) {
        this(secret, accessTtl, Clock.systemUTC());
    }

    JwtIssuerImpl(String secret, Duration accessTtl, Clock clock) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least 32 bytes (256 bits) for HS256. Got " + keyBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTtl = accessTtl;
        this.clock = clock;
    }

    @Override
    public String issueAccessToken(UUID userId, String email) {
        Instant now = Instant.now(clock);
        Instant expiry = now.plus(accessTtl);
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim(CLAIM_EMAIL, email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public int accessTokenTtlSeconds() {
        return Math.toIntExact(accessTtl.toSeconds());
    }

    @Override
    public Optional<AccessTokenClaims> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(Instant.now(clock)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.get(CLAIM_EMAIL, String.class);
            Instant issuedAt = claims.getIssuedAt().toInstant();
            Instant expiresAt = claims.getExpiration().toInstant();
            return Optional.of(new AccessTokenClaims(userId, email, issuedAt, expiresAt));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
