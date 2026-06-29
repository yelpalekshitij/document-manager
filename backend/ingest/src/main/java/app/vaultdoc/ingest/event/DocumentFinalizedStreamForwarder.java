package app.vaultdoc.ingest.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import app.vaultdoc.core.pipeline.event.PipelineEvent;
import app.vaultdoc.core.pipeline.stream.StreamNames;
import app.vaultdoc.core.pipeline.stream.StreamPublisher;

/**
 * AFTER_COMMIT bridge between the in-process {@link DocumentFinalizedEvent}
 * and the Redis Streams pipeline. Publishing only on commit guarantees a
 * consumer never reads an event for an uncommitted (and therefore invisible)
 * document row.
 *
 * <p>Failures here are logged but never propagated — the upload itself
 * succeeded; OCR is a downstream concern and lost events should be replayed
 * via the DLQ tooling (Phase 2).
 */
@Component
public class DocumentFinalizedStreamForwarder {

    private static final Logger log = LoggerFactory.getLogger(DocumentFinalizedStreamForwarder.class);

    private final StreamPublisher publisher;

    public DocumentFinalizedStreamForwarder(StreamPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentFinalized(DocumentFinalizedEvent event) {
        try {
            var pipelineEvent = new PipelineEvent(
                    event.documentId(),
                    event.userId(),
                    event.mimeType(),
                    event.storageKey(),
                    event.occurredAt());
            String id = publisher.publish(StreamNames.UPLOAD_FINALIZED, pipelineEvent);
            log.debug("Published upload-finalized documentId={} entryId={}", event.documentId(), id);
        } catch (Exception e) {
            log.error("Failed to forward DocumentFinalizedEvent documentId={} to Redis stream",
                    event.documentId(), e);
        }
    }
}
