package app.vaultdoc.core.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import app.vaultdoc.core.auth.domain.RefreshToken;
import app.vaultdoc.core.auth.domain.RefreshTokenRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and rotates opaque refresh tokens. Tokens themselves are 256-bit random
 * strings handed to the client; the database stores only their SHA-256 hash, so
 * even a full DB leak does not give an attacker a usable refresh token.
 *
 * <p>Rotation rule: every {@link #rotate} issues a new row in the same
 * {@code sessionId} and revokes the previous one. If a revoked (or unknown)
 * token is ever presented again, the whole session family is revoked — that's
 * how we detect refresh-token theft.
 */
public class RefreshTokenService {

    private static final int RAW_TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository, Duration ttl) {
        this.repository = repository;
        this.ttl = ttl;
    }

    public record IssueResult(UUID userId, UUID sessionId, String rawToken, Instant expiresAt) {
    }

    @Transactional
    public IssueResult issueForNewSession(UUID userId, String userAgent, String ipAddress) {
        UUID sessionId = UUID.randomUUID();
        return issue(userId, sessionId, userAgent, ipAddress);
    }

    @Transactional
    public IssueResult rotate(String presentedToken, String userAgent, String ipAddress) {
        String hash = sha256(presentedToken);
        RefreshToken existing = repository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Unknown refresh token"));

        if (!existing.isActive(Instant.now())) {
            // Reuse-after-revoke or expired → torch the entire session.
            repository.revokeSession(existing.getSessionId());
            throw new InvalidRefreshTokenException("Refresh token revoked or expired");
        }

        existing.revoke();
        repository.save(existing);
        return issue(existing.getUserId(), existing.getSessionId(), userAgent, ipAddress);
    }

    @Transactional
    public void revoke(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(sha256(presentedToken)).ifPresent(t -> {
            t.revoke();
            repository.save(t);
        });
    }

    private IssueResult issue(UUID userId, UUID sessionId, String userAgent, String ipAddress) {
        byte[] raw = new byte[RAW_TOKEN_BYTES];
        RNG.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = Instant.now().plus(ttl);
        RefreshToken entity = new RefreshToken(
                userId, sessionId, sha256(token), expiresAt, userAgent, ipAddress);
        repository.save(entity);
        return new IssueResult(userId, sessionId, token, expiresAt);
    }

    private static String sha256(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
