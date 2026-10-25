package app.vaultdoc.ocr.consumer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.vaultdoc.core.pipeline.stream.StreamNames;

class PendingEntryReclaimerTest {

    private static final String GROUP = "ocr-test";
    private static final String ME = "ocr-host-new";
    private static final Duration MIN_IDLE = Duration.ofMinutes(30);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
    private final UploadFinalizedConsumer consumer = mock(UploadFinalizedConsumer.class);
    private final PendingEntryReclaimer reclaimer =
            new PendingEntryReclaimer(redis, consumer, GROUP, ME, MIN_IDLE, 3, 50);

    @BeforeEach
    void setUp() {
        when(redis.opsForStream()).thenReturn(streamOps);
    }

    @Test
    void idle_entry_of_a_dead_consumer_is_claimed_and_processed() {
        givenPending(pending("5-0", "ocr-host-dead", 1));
        givenClaimed("5-0", Map.of("documentId", "d1", "mimeType", "application/pdf"));

        assertThat(reclaimer.reclaim()).isEqualTo(1);

        verify(streamOps).claim(StreamNames.UPLOAD_FINALIZED, GROUP, ME, MIN_IDLE, RecordId.of("5-0"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MapRecord<String, String, String>> processed = ArgumentCaptor.forClass(MapRecord.class);
        verify(consumer).onMessage(processed.capture());
        assertThat(processed.getValue().getId()).isEqualTo(RecordId.of("5-0"));
        assertThat(processed.getValue().getStream()).isEqualTo(StreamNames.UPLOAD_FINALIZED);
        assertThat(processed.getValue().getValue()).containsEntry("documentId", "d1").containsEntry("mimeType", "application/pdf");
        verify(consumer, never()).deadLetter(any(), anyString());
    }

    @Test
    void only_entries_idle_longer_than_min_idle_are_asked_for() {
        givenPending();

        reclaimer.reclaim();

        verify(streamOps).pending(eq(StreamNames.UPLOAD_FINALIZED), eq(GROUP), eq(Range.unbounded()), eq(50L), eq(MIN_IDLE));
    }

    @Test
    void entry_delivered_max_times_is_dead_lettered_not_retried() {
        givenPending(pending("6-0", "ocr-host-dead", 3));
        givenClaimed("6-0", Map.of("documentId", "d2"));

        assertThat(reclaimer.reclaim()).isEqualTo(1);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(consumer).deadLetter(any(), reason.capture());
        assertThat(reason.getValue()).contains("3");
        verify(consumer, never()).onMessage(any());
    }

    @Test
    void entry_claimed_by_another_worker_first_is_skipped() {
        givenPending(pending("7-0", "ocr-host-dead", 1));
        when(streamOps.claim(eq(StreamNames.UPLOAD_FINALIZED), eq(GROUP), eq(ME), eq(MIN_IDLE), any(RecordId[].class)))
                .thenReturn(List.of());

        assertThat(reclaimer.reclaim()).isZero();

        verify(consumer, never()).onMessage(any());
        verify(consumer, never()).deadLetter(any(), anyString());
    }

    @Test
    void nothing_pending_does_nothing() {
        givenPending();

        assertThat(reclaimer.reclaim()).isZero();

        verify(streamOps, never()).claim(anyString(), anyString(), anyString(), any(Duration.class), any(RecordId[].class));
    }

    @Test
    void redis_failure_is_contained() {
        when(streamOps.pending(anyString(), anyString(), any(Range.class), anyLong(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(reclaimer.reclaim()).isZero();
    }

    @Test
    void one_bad_entry_does_not_stop_the_sweep() {
        givenPending(pending("8-0", "dead", 1), pending("9-0", "dead", 1));
        when(streamOps.claim(StreamNames.UPLOAD_FINALIZED, GROUP, ME, MIN_IDLE, RecordId.of("8-0")))
                .thenThrow(new RedisConnectionFailureException("blip"));
        givenClaimed("9-0", Map.of("documentId", "d9"));

        assertThat(reclaimer.reclaim()).isEqualTo(1);

        verify(consumer).onMessage(any());
    }

    private void givenPending(PendingMessage... messages) {
        when(streamOps.pending(anyString(), anyString(), any(Range.class), anyLong(), any(Duration.class)))
                .thenReturn(new PendingMessages(GROUP, List.of(messages)));
    }

    private void givenClaimed(String id, Map<String, String> body) {
        MapRecord<String, Object, Object> record = StreamRecords.newRecord()
                .in(StreamNames.UPLOAD_FINALIZED)
                .withId(RecordId.of(id))
                .ofMap(Map.<Object, Object>copyOf(body));
        when(streamOps.claim(StreamNames.UPLOAD_FINALIZED, GROUP, ME, MIN_IDLE, RecordId.of(id)))
                .thenReturn(List.of(record));
    }

    private static PendingMessage pending(String id, String owner, long deliveries) {
        return new PendingMessage(RecordId.of(id), Consumer.from(GROUP, owner), Duration.ofMinutes(45), deliveries);
    }
}
