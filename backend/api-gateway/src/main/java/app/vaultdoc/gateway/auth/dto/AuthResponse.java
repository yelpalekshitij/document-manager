package app.vaultdoc.gateway.auth.dto;

import app.vaultdoc.core.auth.service.AuthenticatedSession;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        int expiresInSeconds,
        MeResponse user) {

    public static AuthResponse from(AuthenticatedSession session) {
        return new AuthResponse(
                session.accessToken(),
                session.refreshToken(),
                session.expiresInSeconds(),
                new MeResponse(
                        session.userId(),
                        session.email(),
                        session.displayName(),
                        session.status(),
                        session.createdAt()));
    }
}
