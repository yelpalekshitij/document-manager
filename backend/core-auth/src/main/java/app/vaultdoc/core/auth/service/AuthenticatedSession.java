package app.vaultdoc.core.auth.service;

import java.time.Instant;
import java.util.UUID;

import app.vaultdoc.core.auth.domain.AccountStatus;

/**
 * Result of a successful authentication. Contains both tokens and a snapshot of
 * the user — enough for the controller to populate the AuthResponse DTO.
 */
public record AuthenticatedSession(
        UUID userId,
        String email,
        String displayName,
        AccountStatus status,
        Instant createdAt,
        String accessToken,
        String refreshToken,
        int expiresInSeconds) {
}
