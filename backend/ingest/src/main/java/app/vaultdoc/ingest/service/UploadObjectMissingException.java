package app.vaultdoc.ingest.service;

import java.util.UUID;

public class UploadObjectMissingException extends RuntimeException {
    private final UUID documentId;

    public UploadObjectMissingException(UUID documentId) {
        super("Upload object missing for document " + documentId);
        this.documentId = documentId;
    }

    public UUID getDocumentId() {
        return documentId;
    }
}
