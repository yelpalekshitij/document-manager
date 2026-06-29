package app.vaultdoc.catalog.service;

import java.util.UUID;

public record RegisterDocumentCommand(
        UUID documentId,
        UUID folderId,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        String storageKey,
        String sha256) {}
