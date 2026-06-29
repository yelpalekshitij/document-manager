package app.vaultdoc.core.auth.token;

import java.time.Instant;
import java.util.UUID;

public record AccessTokenClaims(
        UUID userId,
        String email,
        Instant issuedAt,
        Instant expiresAt) {
}
