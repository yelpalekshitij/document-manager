package app.vaultdoc.ingest.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vaultdoc.ingest")
public record IngestProperties(
        /** Maximum size of a single upload. Default 50 MB. */
        long maxSizeBytes,
        /** Whitelisted MIME types. */
        List<String> allowedMimeTypes) {

    public IngestProperties {
        if (maxSizeBytes <= 0) maxSizeBytes = 52_428_800L; // 50 MB
        if (allowedMimeTypes == null || allowedMimeTypes.isEmpty()) {
            allowedMimeTypes = List.of(
                    "application/pdf",
                    "image/jpeg",
                    "image/png",
                    "image/webp",
                    "image/heic",
                    "image/tiff",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            );
        }
    }
}
