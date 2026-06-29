package app.vaultdoc.catalog.service;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {
    private final UUID documentId;

    public DocumentNotFoundException(UUID documentId) {
        super("Document " + documentId + " not found");
        this.documentId = documentId;
    }

    public UUID getDocumentId() {
        return documentId;
    }
}
