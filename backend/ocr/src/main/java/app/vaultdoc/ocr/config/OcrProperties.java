package app.vaultdoc.ocr.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vaultdoc.ocr")
public record OcrProperties(
        /** Consumer-group name on the upload-finalized stream. */
        String consumerGroup,
        /** Optional explicit consumer name. Defaults to hostname + random suffix. */
        String consumerName,
        /** How many messages to fetch per XREADGROUP call. */
        int batchSize,
        /** Block timeout per XREADGROUP poll. */
        Duration pollTimeout,
        /** Max bytes of extracted text stored on the document row. */
        int extractedTextLimitBytes) {

    public OcrProperties {
        if (consumerGroup == null || consumerGroup.isBlank()) {
            consumerGroup = "vaultdoc-ocr-consumers";
        }
        if (batchSize <= 0) batchSize = 10;
        if (pollTimeout == null) pollTimeout = Duration.ofSeconds(2);
        if (extractedTextLimitBytes <= 0) extractedTextLimitBytes = 2_000_000;
    }

    public String resolvedConsumerName() {
        if (consumerName != null && !consumerName.isBlank()) return consumerName;
        String host = System.getenv().getOrDefault("HOSTNAME", "local");
        return "ocr-" + host + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
