package app.vaultdoc.core.auth.service;

import java.time.Instant;
import java.util.UUID;

import app.vaultdoc.core.auth.domain.AccountStatus;
import app.vaultdoc.core.auth.domain.User;

/**
 * Result of {@link AuthService#register(RegisterCommand)}. No tokens — the user
 * must verify their email before signing in.
 */
public record RegistrationResult(
        UUID userId,
        String email,
        String displayName,
        AccountStatus status,
        Instant createdAt,
        boolean verificationRequired) {

    public static RegistrationResult from(User user, boolean verificationRequired) {
        return new RegistrationResult(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                user.getCreatedAt(),
                verificationRequired);
    }
}
