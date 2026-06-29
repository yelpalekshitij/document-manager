package app.vaultdoc.core.auth.twofa;

public record TwoFactorSetupStarted(
        String otpauthUri,
        String accountName) {
}
