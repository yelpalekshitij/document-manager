package app.vaultdoc.search.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Flattened representation of a Document for the search index. Phase 2 adds
 * {@code ocrText} and an {@code extractedFields} map; today the index covers
 * filename + folder metadata only.
 */
public record DocumentIndexRecord(
        UUID documentId,
        UUID userId,
        String filename,
        String mimeType,
        long sizeBytes,
        UUID folderId,
        String folderPath,
        String documentType,
        List<String> tags,
        Instant uploadedAt,
        String ocrText) {

    /** Convenience constructor for the pre-1.3 callers that don't yet pass OCR text. */
    public DocumentIndexRecord(
            UUID documentId,
            UUID userId,
            String filename,
            String mimeType,
            long sizeBytes,
            UUID folderId,
            String folderPath,
            String documentType,
            List<String> tags,
            Instant uploadedAt) {
        this(documentId, userId, filename, mimeType, sizeBytes,
                folderId, folderPath, documentType, tags, uploadedAt, null);
    }
}
