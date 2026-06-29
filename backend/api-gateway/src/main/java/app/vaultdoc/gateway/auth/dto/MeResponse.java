package app.vaultdoc.gateway.auth.dto;

import java.time.Instant;
import java.util.UUID;

import app.vaultdoc.core.auth.domain.AccountStatus;
import app.vaultdoc.core.auth.domain.User;

public record MeResponse(
        UUID id,
        String email,
        String displayName,
        AccountStatus status,
        Instant createdAt) {

    public static MeResponse from(User user) {
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                user.getCreatedAt());
    }
}
