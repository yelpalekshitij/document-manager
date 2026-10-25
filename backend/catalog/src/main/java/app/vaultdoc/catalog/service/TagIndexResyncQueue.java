package app.vaultdoc.catalog.service;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Durable "re-push this document's tags to the search index" work list, for
 * syncs that failed after the tag change had already committed (search down,
 * bulk rejected). A periodic sweep drains it with backoff, so the index
 * converges without waiting for the next edit of that document.
 */
public interface TagIndexResyncQueue {

    /** {@code generation} changes on every re-enqueue, so a sweep never deletes a newer failure. */
    record Item(UUID documentId, UUID userId, long generation, int attempts) {}

    /** Queue (or re-queue, due now) the user's documents; ids that aren't the user's are ignored. */
    void enqueue(UUID userId, Collection<UUID> documentIds, String error);

    /** Up to {@code limit} items whose next attempt is due, oldest first. */
    List<Item> due(int limit);

    /** Synced: remove the items, unless re-queued since they were read. */
    void completed(Collection<Item> items);

    /** Still failing: count the attempt and back off (1, 2, 4 … 60 minutes). */
    void failed(Collection<Item> items, String error);
}
