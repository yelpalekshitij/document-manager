package app.vaultdoc.ingest.service;

import java.util.UUID;

public class VirusDetectedException extends RuntimeException {
    private final UUID documentId;

    public VirusDetectedException(UUID documentId) {
        super("Virus detected in upload for document " + documentId);
        this.documentId = documentId;
    }

    public UUID getDocumentId() {
        return documentId;
    }
}
