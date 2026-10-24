package app.vaultdoc.search.domain;

import java.util.List;
import java.util.UUID;

/**
 * The complete, current tag set of one document — ids for filtering, names for
 * free-text matching. Applied as a partial update so the rest of the indexed
 * document (filename, OCR text, …) is untouched.
 */
public record DocumentTagsUpdate(UUID documentId, List<UUID> tagIds, List<String> tagNames) {

    public DocumentTagsUpdate {
        tagIds = List.copyOf(tagIds);
        tagNames = List.copyOf(tagNames);
    }
}
