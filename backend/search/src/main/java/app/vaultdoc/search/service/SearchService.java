package app.vaultdoc.search.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import app.vaultdoc.search.domain.DocumentIndexRecord;
import app.vaultdoc.search.domain.DocumentTagsUpdate;

/**
 * Phase 1.2 indexes filename + folder metadata only. Phase 2 will extend the
 * mapping with {@code ocrText} (BM25-scored) and {@code extractedFields}.
 *
 * <p>Every operation is user-scoped: callers pass {@code userId} so the
 * implementation can enforce tenant isolation in the query itself.
 *
 * <p>Tag fields ({@code tags}, {@code tagIds}) are owned by {@link #updateTags}:
 * {@link #index} merges into the existing document and never writes them, so a
 * re-index from another pipeline stage (e.g. OCR) cannot wipe a document's tags.
 */
public interface SearchService {

    /** Upsert-merge the record's fields. {@link DocumentIndexRecord#tags()} is ignored. */
    void index(DocumentIndexRecord record);

    /**
     * Replace the tag fields of already-indexed documents. Documents missing
     * from the index (not yet finalized, or already deleted) are skipped.
     */
    void updateTags(UUID userId, List<DocumentTagsUpdate> updates);

    void delete(UUID userId, UUID documentId);

    default SearchPage search(UUID userId, String query, UUID folderId, int page, int pageSize) {
        return search(userId, query, folderId, Set.of(), page, pageSize);
    }

    /**
     * {@code tagIds} is a filter with AND semantics (a hit must carry every
     * id); an empty set means no tag filter. Unknown ids match nothing.
     */
    SearchPage search(UUID userId, String query, UUID folderId, Set<UUID> tagIds, int page, int pageSize);
}
