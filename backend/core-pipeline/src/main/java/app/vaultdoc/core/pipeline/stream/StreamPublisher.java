package app.vaultdoc.core.pipeline.stream;

import java.util.Map;

import app.vaultdoc.core.pipeline.event.PipelineEvent;

/**
 * Pluggable producer for Redis-Streams-style pipeline events. The default
 * implementation talks to Redis, but tests can swap in an in-memory recorder.
 */
public interface StreamPublisher {

    /** Publish a raw entry to the given stream. Returns the entry id. */
    String publish(String stream, Map<String, String> entry);

    default String publish(String stream, PipelineEvent event) {
        return publish(stream, event.toMap());
    }
}
