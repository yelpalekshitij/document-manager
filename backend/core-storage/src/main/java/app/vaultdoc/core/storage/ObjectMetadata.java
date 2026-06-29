package app.vaultdoc.core.storage;

import java.time.Instant;

public record ObjectMetadata(
        String contentType,
        long contentLength,
        String etag,
        Instant lastModified) {}
