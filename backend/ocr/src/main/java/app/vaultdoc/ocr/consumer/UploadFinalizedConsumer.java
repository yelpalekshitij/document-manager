package app.vaultdoc.ocr.consumer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;

import app.vaultdoc.catalog.domain.OcrStatus;
import app.vaultdoc.catalog.service.DocumentNotFoundException;
import app.vaultdoc.catalog.service.DocumentService;
import app.vaultdoc.core.pipeline.event.PipelineEvent;
import app.vaultdoc.core.pipeline.stream.StreamNames;
import app.vaultdoc.core.pipeline.stream.StreamPublisher;
import app.vaultdoc.core.storage.StorageKey;
import app.vaultdoc.ocr.service.OcrResult;
import app.vaultdoc.ocr.service.OcrService;
import app.vaultdoc.search.domain.DocumentIndexRecord;
import app.vaultdoc.search.service.SearchService;

/**
 * Spring Data Redis stream listener for {@link StreamNames#UPLOAD_FINALIZED}.
 *
 * <p>Each message is processed independently:
 * <ol>
 *   <li>Mark the document {@code OCR_PROCESSING}.</li>
 *   <li>Run the OCR service for the document's MIME type.</li>
 *   <li>Persist the result (status + text + page count) on the document row.</li>
 *   <li>Re-index Elasticsearch with the fresh {@code ocrText} payload.</li>
 *   <li>XACK the stream entry.</li>
 * </ol>
 *
 * <p>On any uncaught error we mark the document {@code FAILED}, XADD the
 * failure metadata to the DLQ stream, and still XACK so the entry doesn't
 * redeliver. Phase 2's monitoring UI will surface DLQ contents.
 */
public class UploadFinalizedConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(UploadFinalizedConsumer.class);

    private final DocumentService documents;
    private final OcrService ocr;
    private final SearchService search;
    private final StreamPublisher publisher;
    private final StringRedisTemplate redis;
    private final String consumerGroup;

    public UploadFinalizedConsumer(
            DocumentService documents,
            OcrService ocr,
            SearchService search,
            StreamPublisher publisher,
            StringRedisTemplate redis,
            String consumerGroup) {
        this.documents = documents;
        this.ocr = ocr;
        this.search = search;
        this.publisher = publisher;
        this.redis = redis;
        this.consumerGroup = consumerGroup;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        RecordId recordId = record.getId();
        Map<String, String> raw = record.getValue();
        UUID documentId = null;
        try {
            PipelineEvent event = PipelineEvent.fromMap(raw);
            documentId = event.documentId();
            handle(event);
            ack(recordId);
        } catch (DocumentNotFoundException missing) {
            // Stale event (doc deleted or never persisted). Just ack.
            log.info("OCR event for unknown document {}, acking", missing.getDocumentId());
            ack(recordId);
        } catch (Exception e) {
            log.error("OCR pipeline error for documentId={} entryId={}", documentId, recordId, e);
            if (documentId != null) {
                safelyMarkFailed(documentId, e);
            }
            publishDlq(raw, e);
            ack(recordId);
        }
    }

    private void handle(PipelineEvent event) {
        documents.markOcrProcessing(event.documentId());
        OcrResult result = ocr.extract(new StorageKey(event.storageKey()), event.mimeType());
        var saved = documents.markOcr(
                event.documentId(),
                result.status(),
                result.text(),
                result.pageCount(),
                result.failureReason());

        // Always re-index — even SKIPPED/FAILED rows benefit from having their
        // ocr status visible in ES queries. Filename + folder + mime are already
        // there from the synchronous index at finalize.
        var indexRecord = new DocumentIndexRecord(
                saved.getId(),
                saved.getUserId(),
                saved.getOriginalFilename(),
                saved.getMimeType(),
                saved.getSizeBytes(),
                saved.getFolderId(),
                null,
                saved.getDocumentType(),
                List.of(),
                saved.getUploadedAt(),
                result.status() == OcrStatus.DONE ? result.text() : null);
        search.index(indexRecord);
    }

    private void safelyMarkFailed(UUID documentId, Exception cause) {
        try {
            documents.markOcr(documentId, OcrStatus.FAILED, null, null, summarize(cause));
        } catch (Exception nested) {
            log.warn("Failed to write OCR_FAILED status for {}", documentId, nested);
        }
    }

    private void publishDlq(Map<String, String> original, Exception cause) {
        try {
            var dlq = new java.util.LinkedHashMap<String, String>(original);
            dlq.put("errorClass", cause.getClass().getName());
            dlq.put("errorMessage", summarize(cause));
            dlq.put("failedAt", java.time.Instant.now().toString());
            publisher.publish(StreamNames.UPLOAD_FINALIZED_DLQ, dlq);
        } catch (Exception nested) {
            log.warn("Failed to publish DLQ entry", nested);
        }
    }

    private void ack(RecordId recordId) {
        try {
            redis.opsForStream().acknowledge(StreamNames.UPLOAD_FINALIZED, consumerGroup, recordId);
        } catch (Exception e) {
            log.warn("Failed to XACK entry {}: {}", recordId, e.toString());
        }
    }

    private static String summarize(Throwable t) {
        String message = t.getMessage();
        if (message == null) return t.getClass().getSimpleName();
        if (message.length() > 500) return message.substring(0, 500) + "…";
        return message;
    }
}
