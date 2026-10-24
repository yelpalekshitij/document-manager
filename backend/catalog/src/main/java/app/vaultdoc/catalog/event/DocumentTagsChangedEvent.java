package app.vaultdoc.catalog.event;

import java.util.Set;
import java.util.UUID;

/**
 * Published (in-process) whenever the tags visible on a set of documents change:
 * assignment edits, tag rename, tag delete. A listener outside the catalog
 * re-indexes those documents after commit — the catalog itself never imports
 * the search module.
 */
public record DocumentTagsChangedEvent(UUID userId, Set<UUID> documentIds) {

    public DocumentTagsChangedEvent {
        documentIds = Set.copyOf(documentIds);
    }
}
