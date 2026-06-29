package app.vaultdoc.core.pipeline.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Helpers for stream consumers. Today: idempotent consumer-group creation
 * (XGROUP CREATE with MKSTREAM, BUSYGROUP swallowed).
 */
public final class StreamConsumerSupport {

    private static final Logger log = LoggerFactory.getLogger(StreamConsumerSupport.class);

    private StreamConsumerSupport() {}

    /**
     * Ensures a consumer group exists on {@code stream}, creating the stream
     * itself if missing. Re-running is safe: BUSYGROUP (already exists) is
     * swallowed.
     */
    public static void ensureGroupExists(StringRedisTemplate redis, String stream, String group) {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
            log.info("Created Redis Streams consumer group group={} stream={}", group, stream);
        } catch (RedisSystemException e) {
            // Lettuce wraps the BUSYGROUP response. Treat any "BUSYGROUP" mention as success.
            String message = rootMessage(e);
            if (message != null && message.contains("BUSYGROUP")) {
                log.debug("Consumer group already exists group={} stream={}", group, stream);
                return;
            }
            throw e;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage();
    }
}
