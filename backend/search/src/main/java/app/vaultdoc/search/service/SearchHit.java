package app.vaultdoc.search.service;

import java.time.Instant;
import java.util.UUID;

public record SearchHit(
        UUID documentId,
        String filename,
        String mimeType,
        long sizeBytes,
        UUID folderId,
        String folderPath,
        String documentType,
        Instant uploadedAt,
        double score) {}
