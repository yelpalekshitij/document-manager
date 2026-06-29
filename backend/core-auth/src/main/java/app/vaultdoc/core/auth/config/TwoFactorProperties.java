package app.vaultdoc.core.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vaultdoc.2fa")
public record TwoFactorProperties(
        String encryptionKey,
        String issuer,
        Duration tokenTtl) {

    public TwoFactorProperties {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            encryptionKey = "dev-only-2fa-enc-key-32chars-exactly!";
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "VaultDoc";
        }
        if (tokenTtl == null) {
            tokenTtl = Duration.ofMinutes(10);
        }
    }
}
