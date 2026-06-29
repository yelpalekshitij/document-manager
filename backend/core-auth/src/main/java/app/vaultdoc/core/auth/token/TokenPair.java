package app.vaultdoc.core.auth.token;

public record TokenPair(
        String accessToken,
        String refreshToken,
        int expiresInSeconds) {
}
