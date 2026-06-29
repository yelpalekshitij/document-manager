package app.vaultdoc.core.auth.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vaultdoc.verification")
public record VerificationProperties(
        Duration tokenTtl,
        URI webBaseUrl,
        String fromAddress,
        Duration resendCooldown) {

    public VerificationProperties {
        if (tokenTtl == null) {
            tokenTtl = Duration.ofHours(24);
        }
        if (webBaseUrl == null) {
            webBaseUrl = URI.create("http://localhost:4200");
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            fromAddress = "no-reply@vaultdoc.local";
        }
        if (resendCooldown == null) {
            resendCooldown = Duration.ofSeconds(60);
        }
    }
}
