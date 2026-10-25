package app.vaultdoc.gateway.documents;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import app.vaultdoc.catalog.service.TagIndexResyncQueue;
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
 * so ES never sees tags that were rolled back. Failures never fail the
 * request — the tag change is already committed — but they are not just
 * logged either: the affected documents go on the durable
 * {@link TagIndexResyncQueue}, and {@link #retryPending()} (run periodically by
 * {@link TagIndexResyncScheduler}) re-pushes their current tags until ES
 * accepts them. {@code index()} never writes tag fields, so nothing else would
 * repair a missed rename or delete.
 *
 * <p>Work is chunked ({@value #CHUNK} documents per catalog query and per bulk
 * request): deleting a tag on tens of thousands of documents must not hit
 * PostgreSQL's bind-parameter limit or build one giant bulk body.
 */
@Component
public class TagIndexSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(TagIndexSynchronizer.class);

    static final int CHUNK = 1_000;
    static final int RETRY_BATCH = 500;

    private final TagService tags;
    private final SearchService search;
    private final TagIndexResyncQueue queue;

    public TagIndexSynchronizer(TagService tags, SearchService search, TagIndexResyncQueue queue) {
        this.tags = tags;
        this.search = search;
        this.queue = queue;
    }

    /** Assignment edits, tag rename, tag delete. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTagsChanged(DocumentTagsChangedEvent event) {
        for (List<UUID> chunk : chunks(event.documentIds())) {
            syncOrQueue(event.userId(), chunk, true);
        }
    }

    /**
     * A document tagged while still PENDING wasn't in the index yet; finalize
     * indexes it without tags, so push them now.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDocumentFinalized(DocumentFinalizedEvent event) {
        syncOrQueue(event.userId(), Set.of(event.documentId()), false);
    }

    /**
     * One pass over the retry queue: re-push current tags for every due
     * document, per user. Never throws.
     *
     * @return documents whose retry succeeded
     */
    public int retryPending() {
        List<TagIndexResyncQueue.Item> due;
        try {
            due = queue.due(RETRY_BATCH);
        } catch (RuntimeException e) {
            log.warn("Tag re-index retry: queue unavailable: {}", e.toString());
            return 0;
        }
        Map<UUID, List<TagIndexResyncQueue.Item>> byUser = new LinkedHashMap<>();
        for (TagIndexResyncQueue.Item item : due) {
            byUser.computeIfAbsent(item.userId(), k -> new ArrayList<>()).add(item);
        }
        int synced = 0;
        for (Map.Entry<UUID, List<TagIndexResyncQueue.Item>> entry : byUser.entrySet()) {
            List<TagIndexResyncQueue.Item> items = entry.getValue();
            Set<UUID> ids = new LinkedHashSet<>();
            items.forEach(i -> ids.add(i.documentId()));
            try {
                sync(entry.getKey(), ids, true);
                queue.completed(items);
                synced += items.size();
            } catch (RuntimeException e) {
                log.warn("Tag re-index retry failed for {} document(s) of user {}: {}",
                        items.size(), entry.getKey(), e.toString());
                try {
                    queue.failed(items, summarize(e));
                } catch (RuntimeException nested) {
                    log.warn("Could not record tag re-index retry failure: {}", nested.toString());
                }
            }
        }
        if (synced > 0) {
            log.info("Tag re-index retry: {} document(s) back in sync", synced);
        }
        return synced;
    }

    private void syncOrQueue(UUID userId, Collection<UUID> documentIds, boolean includeUntagged) {
        try {
            sync(userId, documentIds, includeUntagged);
        } catch (RuntimeException e) {
            log.error("Failed to re-index tags for {} document(s) of user {}; queued for retry",
                    documentIds.size(), userId, e);
            try {
                queue.enqueue(userId, new LinkedHashSet<>(documentIds), summarize(e));
            } catch (RuntimeException nested) {
                log.error("Could not queue tag re-index retry for {} document(s) of user {}",
                        documentIds.size(), userId, nested);
            }
        }
    }

    private void sync(UUID userId, Collection<UUID> documentIds, boolean includeUntagged) {
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
    }

    private static List<List<UUID>> chunks(Collection<UUID> ids) {
        List<List<UUID>> out = new ArrayList<>();
        List<UUID> current = new ArrayList<>(Math.min(ids.size(), CHUNK));
        for (UUID id : ids) {
            current.add(id);
            if (current.size() == CHUNK) {
                out.add(current);
                current = new ArrayList<>(CHUNK);
            }
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    private static String summarize(Throwable t) {
        String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return message.length() > 500 ? message.substring(0, 500) + "…" : message;
    }
}
