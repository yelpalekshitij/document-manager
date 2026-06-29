package app.vaultdoc.core.auth.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vaultdoc.passkey")
public record PasskeyProperties(
        String rpId,
        String rpName,
        List<String> origins,
        Duration challengeTtl) {

    public PasskeyProperties {
        if (rpId == null || rpId.isBlank()) {
            rpId = "localhost";
        }
        if (rpName == null || rpName.isBlank()) {
            rpName = "VaultDoc";
        }
        if (origins == null || origins.isEmpty()) {
            origins = List.of("http://localhost:4200");
        }
        if (challengeTtl == null) {
            challengeTtl = Duration.ofMinutes(5);
        }
    }
}
