package app.vaultdoc.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A document whose tags still have to reach the search index (see V9). Read
 * through JPA; every write is a native statement in
 * {@link TagIndexResyncRepository} so concurrent enqueues upsert atomically.
 */
@Entity
@Table(name = "tag_index_resync")
public class TagIndexResync {

    @Id
    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "generation", nullable = false)
    private long generation;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "enqueued_at", nullable = false)
    private Instant enqueuedAt;

    protected TagIndexResync() {}

    public UUID getDocumentId() { return documentId; }
    public UUID getUserId() { return userId; }
    public long getGeneration() { return generation; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getEnqueuedAt() { return enqueuedAt; }
}
