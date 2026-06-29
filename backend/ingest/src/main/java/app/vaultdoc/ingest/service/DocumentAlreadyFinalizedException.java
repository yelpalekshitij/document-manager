package app.vaultdoc.ingest.service;

import java.util.UUID;

public class DocumentAlreadyFinalizedException extends RuntimeException {
    private final UUID documentId;

    public DocumentAlreadyFinalizedException(UUID documentId) {
        super("Document " + documentId + " is already finalized");
        this.documentId = documentId;
    }

    public UUID getDocumentId() {
        return documentId;
    }
}
