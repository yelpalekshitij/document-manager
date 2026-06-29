package app.vaultdoc.core.auth.service;

public record RegisterCommand(
        String email,
        char[] password,
        String displayName) {
}
