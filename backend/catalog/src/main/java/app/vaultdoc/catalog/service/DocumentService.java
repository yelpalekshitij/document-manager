package app.vaultdoc.catalog.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import app.vaultdoc.catalog.domain.Document;
import app.vaultdoc.catalog.domain.OcrStatus;

public interface DocumentService {

    Document register(UUID userId, RegisterDocumentCommand cmd);

    Document findByIdForUser(UUID userId, UUID documentId);

    default Page<Document> list(UUID userId, UUID folderId, Pageable pageable) {
        return list(userId, folderId, Set.of(), pageable);
    }

    /**
     * Non-deleted documents, newest first. {@code tagIds} (AND semantics) is
     * expected pre-normalised via {@link TagInput#normalizeFilter}; an empty set
     * means "no tag filter". Ids the user doesn't own simply match nothing.
     */
    Page<Document> list(UUID userId, UUID folderId, Set<UUID> tagIds, Pageable pageable);

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
