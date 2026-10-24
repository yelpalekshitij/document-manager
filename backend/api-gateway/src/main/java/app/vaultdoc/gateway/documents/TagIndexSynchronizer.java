package app.vaultdoc.gateway.documents;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import app.vaultdoc.catalog.domain.Tag;
import app.vaultdoc.catalog.event.DocumentTagsChangedEvent;
import app.vaultdoc.catalog.service.TagService;
import app.vaultdoc.ingest.event.DocumentFinalizedEvent;
import app.vaultdoc.search.domain.DocumentTagsUpdate;
import app.vaultdoc.search.service.SearchService;

/**
 * Keeps the search index's tag fields in step with the catalog. Lives in the
 * gateway because it is the one place that sees both {@code catalog} and
 * {@code search} — neither module imports the other.
 *
 * <p>Runs AFTER_COMMIT (same pattern as {@code DocumentFinalizedStreamForwarder})
 * so ES never sees tags that were rolled back. Failures are logged, never
 * propagated: the tag change is already committed, and the next change to the
 * document re-sends its full tag set.
 */
@Component
public class TagIndexSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(TagIndexSynchronizer.class);

    private final TagService tags;
    private final SearchService search;

    public TagIndexSynchronizer(TagService tags, SearchService search) {
        this.tags = tags;
        this.search = search;
    }

    /** Assignment edits, tag rename, tag delete. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTagsChanged(DocumentTagsChangedEvent event) {
        sync(event.userId(), event.documentIds(), true);
    }

    /**
     * A document tagged while still PENDING wasn't in the index yet; finalize
     * indexes it without tags, so push them now.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDocumentFinalized(DocumentFinalizedEvent event) {
        sync(event.userId(), Set.of(event.documentId()), false);
    }

    private void sync(UUID userId, Collection<UUID> documentIds, boolean includeUntagged) {
        try {
            Map<UUID, List<Tag>> current = tags.tagsForIndexing(userId, documentIds);
            List<DocumentTagsUpdate> updates = current.entrySet().stream()
                    // An untagged doc only needs a write when tags were just removed from it.
                    .filter(e -> includeUntagged || !e.getValue().isEmpty())
                    .map(e -> new DocumentTagsUpdate(
                            e.getKey(),
                            e.getValue().stream().map(Tag::getId).toList(),
                            e.getValue().stream().map(Tag::getName).toList()))
                    .toList();
            if (!updates.isEmpty()) {
                search.updateTags(userId, updates);
            }
        } catch (RuntimeException e) {
            log.error("Failed to re-index tags for {} document(s) of user {}", documentIds.size(), userId, e);
        }
    }
}
