package app.vaultdoc.search.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Flattened representation of a Document for the search index. Phase 2 adds
 * {@code ocrText} and an {@code extractedFields} map; today the index covers
 * filename + folder metadata only.
 *
 * <p>{@code tags} is ignored by {@code SearchService.index}: since Phase 1.4 the
 * tag fields are owned by {@code SearchService.updateTags}, so stages that don't
 * know a document's tags (finalize, OCR) can't overwrite them.
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
