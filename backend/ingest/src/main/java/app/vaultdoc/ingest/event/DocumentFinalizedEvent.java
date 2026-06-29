package app.vaultdoc.ingest.event;

import java.time.Instant;
import java.util.UUID;

/**
 * In-process Spring event published when a document finishes the synchronous
 * portion of {@code finalize-upload}. A {@code @TransactionalEventListener}
 * forwards this to the Redis stream after the JPA transaction commits.
 *
 * <p>The Redis forwarding is a separate step so unit tests of
 * {@code IngestServiceImpl} don't need Redis.
 */
public record DocumentFinalizedEvent(
        UUID documentId,
        UUID userId,
        String mimeType,
        String storageKey,
        Instant occurredAt) {}
