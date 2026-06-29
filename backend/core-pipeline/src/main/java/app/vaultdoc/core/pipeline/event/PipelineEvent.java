package app.vaultdoc.core.pipeline.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Common envelope for every stage of the document pipeline. Serialized as a
 * flat {@code Map<String, String>} for Redis Streams entries — no JSON
 * wrapping, no schema registry.
 */
public record PipelineEvent(
        UUID documentId,
        UUID userId,
        String mimeType,
        String storageKey,
        Instant eventTime) {

    public Map<String, String> toMap() {
        var map = new LinkedHashMap<String, String>(5);
        map.put("documentId", documentId.toString());
        map.put("userId", userId.toString());
        map.put("mimeType", mimeType == null ? "" : mimeType);
        map.put("storageKey", storageKey == null ? "" : storageKey);
        map.put("eventTime", (eventTime == null ? Instant.now() : eventTime).toString());
        return map;
    }

    public static PipelineEvent fromMap(Map<String, String> map) {
        return new PipelineEvent(
                UUID.fromString(requireNonBlank(map.get("documentId"), "documentId")),
                UUID.fromString(requireNonBlank(map.get("userId"), "userId")),
                emptyToNull(map.get("mimeType")),
                emptyToNull(map.get("storageKey")),
                Instant.parse(requireNonBlank(map.get("eventTime"), "eventTime")));
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PipelineEvent." + name + " missing");
        }
        return value;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
