package app.vaultdoc.core.auth.service;

public record LoginCommand(
        String email,
        char[] password,
        String userAgent,
        String ipAddress) {
}
