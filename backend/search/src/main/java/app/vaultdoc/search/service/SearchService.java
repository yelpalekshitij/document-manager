package app.vaultdoc.search.service;

import java.util.UUID;

import app.vaultdoc.search.domain.DocumentIndexRecord;

/**
 * Phase 1.2 indexes filename + folder metadata only. Phase 2 will extend the
 * mapping with {@code ocrText} (BM25-scored) and {@code extractedFields}.
 *
 * <p>Every operation is user-scoped: callers pass {@code userId} so the
 * implementation can enforce tenant isolation in the query itself.
 */
public interface SearchService {

    void index(DocumentIndexRecord record);

    void delete(UUID userId, UUID documentId);

    SearchPage search(UUID userId, String query, UUID folderId, int page, int pageSize);
}
