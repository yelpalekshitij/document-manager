package app.vaultdoc.ocr.consumer;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import app.vaultdoc.core.pipeline.stream.StreamNames;

/**
 * Recovers {@link StreamNames#UPLOAD_FINALIZED} entries that were delivered
 * but never acknowledged — the consumer's JVM died mid-job (deploy, OOM kill,
 * pod eviction). The listener only reads new entries ({@code >}) and every
 * boot gets a fresh consumer name, so without this such an entry would sit in
 * a dead consumer's pending list forever and the document would show
 * {@code PROCESSING} for good.
 *
 * <p>Each sweep asks Redis for entries idle longer than {@code minIdle}
 * (XPENDING … IDLE), XCLAIMs them for this consumer and runs them through the
 * normal handler — processing is idempotent (same row and ES doc overwritten).
 * An entry already delivered {@code maxDeliveries} times is a poison pill
 * (it keeps killing the worker): it is dead-lettered and acked instead.
 *
 * <p>{@code minIdle} must exceed the longest legitimate job (the OCR document
 * budget plus slack), or a live consumer's in-flight entry would be claimed
 * too — harmless for correctness, but wasted work.
 */
public class PendingEntryReclaimer {

    private static final Logger log = LoggerFactory.getLogger(PendingEntryReclaimer.class);

    private final StringRedisTemplate redis;
    private final UploadFinalizedConsumer consumer;
    private final String group;
    private final String consumerName;
    private final Duration minIdle;
    private final int maxDeliveries;
    private final int batchSize;
    private ScheduledExecutorService scheduler;

    public PendingEntryReclaimer(
            StringRedisTemplate redis,
            UploadFinalizedConsumer consumer,
            String group,
            String consumerName,
            Duration minIdle,
            int maxDeliveries,
            int batchSize) {
        this.redis = redis;
        this.consumer = consumer;
        this.group = group;
        this.consumerName = consumerName;
        this.minIdle = minIdle;
        this.maxDeliveries = maxDeliveries;
        this.batchSize = batchSize;
    }

    public String consumerName() {
        return consumerName;
    }

    public Duration minIdle() {
        return minIdle;
    }

    public int maxDeliveries() {
        return maxDeliveries;
    }

    /** Sweep every {@code interval} on a dedicated daemon thread. */
    public synchronized void start(Duration interval) {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("ocr-pending-reclaimer").factory());
        scheduler.scheduleWithFixedDelay(this::reclaim, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("OCR pending-entry reclaimer started: consumer={} minIdle={} interval={} maxDeliveries={}",
                consumerName, minIdle, interval, maxDeliveries);
    }

    public synchronized void stop() {
        if (scheduler == null) return;
        scheduler.shutdownNow();
        scheduler = null;
    }

    /**
     * One sweep. Never throws — a Redis outage just means "try again next time".
     *
     * @return entries handled (processed or dead-lettered)
     */
    public int reclaim() {
        PendingMessages pending;
        try {
            pending = redis.opsForStream()
                    .pending(StreamNames.UPLOAD_FINALIZED, group, Range.unbounded(), batchSize, minIdle);
        } catch (RuntimeException e) {
            log.warn("OCR reclaim: could not list pending entries: {}", e.toString());
            return 0;
        }
        int handled = 0;
        for (PendingMessage message : pending) {
            try {
                if (handle(message)) handled++;
            } catch (RuntimeException e) {
                log.warn("OCR reclaim: entry {} (owner {}) not handled: {}",
                        message.getIdAsString(), message.getConsumerName(), e.toString());
            }
        }
        if (handled > 0) {
            log.info("OCR reclaim: recovered {} stranded entr{}", handled, handled == 1 ? "y" : "ies");
        }
        return handled;
    }

    private boolean handle(PendingMessage message) {
        List<MapRecord<String, Object, Object>> claimed = redis.opsForStream()
                .claim(StreamNames.UPLOAD_FINALIZED, group, consumerName, minIdle, message.getId());
        // Empty: another worker claimed it first, or it was acked/trimmed meanwhile.
        if (claimed == null || claimed.isEmpty()) return false;
        MapRecord<String, String, String> record = asStrings(claimed.getFirst());
        log.warn("OCR reclaim: claimed entry {} from consumer {} (idle {}, delivered {}x)",
                message.getIdAsString(), message.getConsumerName(),
                message.getElapsedTimeSinceLastDelivery(), message.getTotalDeliveryCount());
        if (message.getTotalDeliveryCount() >= maxDeliveries) {
            consumer.deadLetter(record, "OCR did not complete after %d delivery attempts (the worker stopped mid-job each time)"
                    .formatted(message.getTotalDeliveryCount()));
        } else {
            consumer.onMessage(record);
        }
        return true;
    }

    private static MapRecord<String, String, String> asStrings(MapRecord<String, Object, Object> raw) {
        Map<String, String> body = new LinkedHashMap<>();
        raw.getValue().forEach((k, v) -> body.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return StreamRecords.newRecord().in(raw.getStream()).withId(raw.getId()).ofMap(body);
    }
}
