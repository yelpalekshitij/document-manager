package app.vaultdoc.gateway.documents.dto;

import java.util.UUID;

public record InitiateUploadResponse(
        UUID documentId,
        String storageKey,
        String uploadUrl,
        long expiresInSeconds) {}
