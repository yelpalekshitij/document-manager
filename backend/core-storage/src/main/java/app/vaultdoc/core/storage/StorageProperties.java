package app.vaultdoc.core.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vaultdoc.storage")
public record StorageProperties(
        /** TTL for presigned PUT URLs. Must comfortably cover slow uploads. */
        Duration presignPutTtl,
        /** TTL for presigned GET URLs (downloads + viewer). Keep short. */
        Duration presignGetTtl) {

    public StorageProperties {
        if (presignPutTtl == null) presignPutTtl = Duration.ofMinutes(15);
        if (presignGetTtl == null) presignGetTtl = Duration.ofMinutes(5);
    }
}
