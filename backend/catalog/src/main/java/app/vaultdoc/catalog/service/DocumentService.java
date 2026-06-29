package app.vaultdoc.catalog.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import app.vaultdoc.catalog.domain.Document;
import app.vaultdoc.catalog.domain.OcrStatus;

public interface DocumentService {

    Document register(UUID userId, RegisterDocumentCommand cmd);

    Document findByIdForUser(UUID userId, UUID documentId);

    Page<Document> list(UUID userId, UUID folderId, Pageable pageable);

    List<Document> recentReady(UUID userId, int limit);

    Document markReady(UUID documentId, String confirmedMimeType, long confirmedSize);

    Document markFailed(UUID documentId);

    /** Worker context: load a document without applying the request-side user-scope filter. */
    Document findByIdForSystem(UUID documentId);

    /** Worker context: flip OCR to PROCESSING. */
    Document markOcrProcessing(UUID documentId);

    /**
     * Worker context: record the outcome of an OCR pass. Status determines which
     * other fields are written; text is capped on the caller side.
     */
    Document markOcr(UUID documentId, OcrStatus status, String text, Integer pageCount, String failureReason);

    void softDelete(UUID userId, UUID documentId);
}
