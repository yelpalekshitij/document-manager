package app.vaultdoc.core.auth.token;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtIssuerImplTest {

    private static final String SECRET = "dev-only-secret-do-not-use-in-production-please-rotate-me";

    @Test
    void rejects_short_secret() {
        assertThrows(IllegalArgumentException.class,
                () -> new JwtIssuerImpl("too-short", Duration.ofMinutes(5)));
    }

    @Test
    void roundtrips_a_token() {
        JwtIssuerImpl issuer = new JwtIssuerImpl(SECRET, Duration.ofMinutes(5));
        UUID userId = UUID.randomUUID();
        String token = issuer.issueAccessToken(userId, "kshitij@example.com");

        Optional<AccessTokenClaims> claims = issuer.verify(token);
        assertTrue(claims.isPresent());
        assertEquals(userId, claims.get().userId());
        assertEquals("kshitij@example.com", claims.get().email());
    }

    @Test
    void rejects_expired_token() {
        Instant fixed = Instant.parse("2030-01-01T00:00:00Z");
        JwtIssuerImpl issuer = new JwtIssuerImpl(
                SECRET, Duration.ofMinutes(5), Clock.fixed(fixed, ZoneOffset.UTC));
        String token = issuer.issueAccessToken(UUID.randomUUID(), "a@b.com");

        JwtIssuerImpl laterClock = new JwtIssuerImpl(
                SECRET, Duration.ofMinutes(5),
                Clock.fixed(fixed.plus(Duration.ofMinutes(10)), ZoneOffset.UTC));
        assertTrue(laterClock.verify(token).isEmpty());
    }

    @Test
    void rejects_wrong_signature() {
        JwtIssuerImpl issuer = new JwtIssuerImpl(SECRET, Duration.ofMinutes(5));
        String token = issuer.issueAccessToken(UUID.randomUUID(), "a@b.com");

        JwtIssuerImpl otherSecret = new JwtIssuerImpl(
                "totally-different-secret-of-sufficient-length-1234", Duration.ofMinutes(5));
        assertTrue(otherSecret.verify(token).isEmpty());
    }

    @Test
    void rejects_garbage_input() {
        JwtIssuerImpl issuer = new JwtIssuerImpl(SECRET, Duration.ofMinutes(5));
        assertTrue(issuer.verify("").isEmpty());
        assertTrue(issuer.verify(null).isEmpty());
        assertTrue(issuer.verify("not-a-jwt").isEmpty());
    }

    @Test
    void exposes_ttl_seconds() {
        JwtIssuerImpl issuer = new JwtIssuerImpl(SECRET, Duration.ofHours(2));
        assertEquals(7200, issuer.accessTokenTtlSeconds());
        assertNotNull(issuer.issueAccessToken(UUID.randomUUID(), "x@y.com"));
    }
}
