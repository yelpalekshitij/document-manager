package app.vaultdoc.ingest.service;

import java.net.URI;
import java.util.UUID;

import app.vaultdoc.catalog.domain.Document;

public interface IngestService {

    record InitiateUploadCommand(
            String filename,
            String mimeType,
            long sizeBytes,
            String sha256,
            UUID folderId) {}

    record UploadTicket(
            UUID documentId,
            URI uploadUrl,
            String storageKey,
            long expiresInSeconds) {}

    UploadTicket initiateUpload(UUID userId, InitiateUploadCommand cmd);

    Document finalizeUpload(UUID userId, UUID documentId);
}
