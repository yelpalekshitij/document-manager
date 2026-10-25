package app.vaultdoc.catalog.service;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import app.vaultdoc.catalog.domain.TagIndexResyncRepository;

/**
 * Postgres-backed {@link TagIndexResyncQueue}. Every write runs in its own
 * transaction: enqueue is called from AFTER_COMMIT listeners, where the
 * request's transaction has already committed and can't take more writes.
 */
public class TagIndexResyncQueueImpl implements TagIndexResyncQueue {

    private static final int MAX_ERROR_CHARS = 1_000;

    private final TagIndexResyncRepository repository;

    public TagIndexResyncQueueImpl(TagIndexResyncRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(UUID userId, Collection<UUID> documentIds, String error) {
        if (documentIds == null || documentIds.isEmpty()) return;
        repository.enqueue(userId, documentIds, truncate(error));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Item> due(int limit) {
        return repository.findDue(limit).stream()
                .map(r -> new Item(r.getDocumentId(), r.getUserId(), r.getGeneration(), r.getAttempts()))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completed(Collection<Item> items) {
        for (Item item : items) {
            repository.deleteIfGeneration(item.documentId(), item.generation());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Collection<Item> items, String error) {
        if (items.isEmpty()) return;
        repository.recordFailure(items.stream().map(Item::documentId).toList(), truncate(error));
    }

    private static String truncate(String error) {
        if (error == null) return null;
        return error.length() <= MAX_ERROR_CHARS ? error : error.substring(0, MAX_ERROR_CHARS) + "…";
    }
}
