package app.vaultdoc.ocr.consumer;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.Document;
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

class UploadFinalizedConsumerTest {

    private static final String GROUP = "ocr-test";

    private final DocumentService documents = mock(DocumentService.class);
    private final OcrService ocr = mock(OcrService.class);
    private final SearchService search = mock(SearchService.class);
    private final StreamPublisher publisher = mock(StreamPublisher.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);

    private final UploadFinalizedConsumer consumer =
            new UploadFinalizedConsumer(documents, ocr, search, publisher, redis, GROUP);

    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final StorageKey key = StorageKey.forDocument(userId, documentId, "scan.png");
    private final Document doc = new Document(documentId, userId, null, "scan.png", "image/png", 10, key.value(), "a".repeat(64));

    @BeforeEach
    void setUp() {
        when(redis.opsForStream()).thenReturn(streamOps);
        when(documents.markOcr(eq(documentId), any(), any(), any(), any())).thenReturn(doc);
    }

    @Test
    void persists_result_reindexes_and_acks() {
        when(ocr.extract(key, "image/png")).thenReturn(OcrResult.done("PASSPORT", 1));

        consumer.onMessage(record("1-0"));

        verify(documents).markOcrProcessing(documentId);
        verify(documents).markOcr(documentId, OcrStatus.DONE, "PASSPORT", 1, null);
        ArgumentCaptor<DocumentIndexRecord> indexed = ArgumentCaptor.forClass(DocumentIndexRecord.class);
        verify(search).index(indexed.capture());
        assertThat(indexed.getValue().ocrText()).isEqualTo("PASSPORT");
        assertThat(indexed.getValue().userId()).isEqualTo(userId);
        verify(streamOps).acknowledge(StreamNames.UPLOAD_FINALIZED, GROUP, RecordId.of("1-0"));
        verify(publisher, never()).publish(any(), anyMap());
    }

    @Test
    void failed_result_persists_reason_and_indexes_without_text() {
        when(ocr.extract(key, "image/png")).thenReturn(OcrResult.failed("tesseract timed out after 60s"));

        consumer.onMessage(record("2-0"));

        verify(documents).markOcr(documentId, OcrStatus.FAILED, null, null, "tesseract timed out after 60s");
        ArgumentCaptor<DocumentIndexRecord> indexed = ArgumentCaptor.forClass(DocumentIndexRecord.class);
        verify(search).index(indexed.capture());
        assertThat(indexed.getValue().ocrText()).isNull();
        verify(streamOps).acknowledge(StreamNames.UPLOAD_FINALIZED, GROUP, RecordId.of("2-0"));
        // A handled engine failure is a result, not a pipeline error — no DLQ.
        verify(publisher, never()).publish(any(), anyMap());
    }

    @Test
    void unexpected_exception_marks_failed_and_dead_letters() {
        when(ocr.extract(key, "image/png")).thenThrow(new IllegalStateException("engine exploded"));

        consumer.onMessage(record("3-0"));

        verify(documents).markOcr(documentId, OcrStatus.FAILED, null, null, "engine exploded");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> dlq = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publish(eq(StreamNames.UPLOAD_FINALIZED_DLQ), dlq.capture());
        assertThat(dlq.getValue()).containsEntry("errorMessage", "engine exploded")
                .containsEntry("documentId", documentId.toString());
        verify(streamOps).acknowledge(StreamNames.UPLOAD_FINALIZED, GROUP, RecordId.of("3-0"));
    }

    @Test
    void unknown_document_is_acked() {
        when(documents.markOcrProcessing(documentId)).thenThrow(new DocumentNotFoundException(documentId));

        consumer.onMessage(record("4-0"));

        verify(ocr, never()).extract(any(), any());
        verify(publisher, never()).publish(any(), anyMap());
        verify(streamOps).acknowledge(StreamNames.UPLOAD_FINALIZED, GROUP, RecordId.of("4-0"));
    }

    @Test
    void replay_is_idempotent() {
        when(ocr.extract(key, "image/png")).thenReturn(OcrResult.done("same text", 1));

        consumer.onMessage(record("5-0"));
        consumer.onMessage(record("5-1"));

        verify(documents, times(2)).markOcr(documentId, OcrStatus.DONE, "same text", 1, null);
        ArgumentCaptor<DocumentIndexRecord> indexed = ArgumentCaptor.forClass(DocumentIndexRecord.class);
        verify(search, times(2)).index(indexed.capture());
        // Same ES document id both times → overwrite, not duplicate.
        assertThat(indexed.getAllValues()).extracting(DocumentIndexRecord::documentId).containsOnly(documentId);
        verify(streamOps).acknowledge(StreamNames.UPLOAD_FINALIZED, GROUP, RecordId.of("5-0"));
        verify(streamOps).acknowledge(StreamNames.UPLOAD_FINALIZED, GROUP, RecordId.of("5-1"));
    }

    @Test
    void malformed_event_is_dead_lettered() {
        Map<String, String> broken = new HashMap<>(event().toMap());
        broken.remove("documentId");

        consumer.onMessage(StreamRecords.newRecord().in(StreamNames.UPLOAD_FINALIZED)
                .withId(RecordId.of("6-0")).ofMap(broken));

        verify(documents, never()).markOcr(any(), any(), isNull(), isNull(), any());
        verify(publisher).publish(eq(StreamNames.UPLOAD_FINALIZED_DLQ), anyMap());
        verify(streamOps).acknowledge(StreamNames.UPLOAD_FINALIZED, GROUP, RecordId.of("6-0"));
    }

    @Test
    void dead_letter_marks_failed_publishes_dlq_and_acks() {
        consumer.deadLetter(record("9-0"), "OCR did not complete after 3 delivery attempts");

        verify(documents).markOcr(documentId, OcrStatus.FAILED, null, null, "OCR did not complete after 3 delivery attempts");
        verify(ocr, never()).extract(any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> dlq = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publish(eq(StreamNames.UPLOAD_FINALIZED_DLQ), dlq.capture());
        assertThat(dlq.getValue()).containsEntry("errorMessage", "OCR did not complete after 3 delivery attempts");
        verify(streamOps).acknowledge(StreamNames.UPLOAD_FINALIZED, GROUP, RecordId.of("9-0"));
    }

    @Test
    void dead_letter_of_malformed_entry_still_acks() {
        MapRecord<String, String, String> junk = StreamRecords.newRecord()
                .in(StreamNames.UPLOAD_FINALIZED).withId(RecordId.of("9-1")).ofMap(Map.of("noise", "x"));

        consumer.deadLetter(junk, "redelivered too often");

        verify(documents, never()).markOcr(any(), any(), any(), any(), any());
        verify(publisher).publish(eq(StreamNames.UPLOAD_FINALIZED_DLQ), anyMap());
        verify(streamOps).acknowledge(StreamNames.UPLOAD_FINALIZED, GROUP, RecordId.of("9-1"));
    }

    private MapRecord<String, String, String> record(String id) {
        return StreamRecords.newRecord().in(StreamNames.UPLOAD_FINALIZED)
                .withId(RecordId.of(id)).ofMap(event().toMap());
    }

    private PipelineEvent event() {
        return new PipelineEvent(documentId, userId, "image/png", key.value(), Instant.now());
    }
}
