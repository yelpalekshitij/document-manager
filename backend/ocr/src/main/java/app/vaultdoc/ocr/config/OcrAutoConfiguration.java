package app.vaultdoc.ocr.config;

import java.time.Duration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.Subscription;

import app.vaultdoc.catalog.service.DocumentService;
import app.vaultdoc.core.pipeline.stream.StreamConsumerSupport;
import app.vaultdoc.core.pipeline.stream.StreamNames;
import app.vaultdoc.core.pipeline.stream.StreamPublisher;
import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.ocr.consumer.UploadFinalizedConsumer;
import app.vaultdoc.ocr.service.OcrService;
import app.vaultdoc.ocr.service.PdfBoxOcrService;
import app.vaultdoc.search.service.SearchService;

@Configuration
@EnableConfigurationProperties(OcrProperties.class)
public class OcrAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OcrAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    OcrService ocrService(ObjectStorage storage, OcrProperties properties) {
        return new PdfBoxOcrService(storage, properties.extractedTextLimitBytes());
    }

    @Bean
    @ConditionalOnMissingBean
    UploadFinalizedConsumer uploadFinalizedConsumer(
            DocumentService documents,
            OcrService ocr,
            SearchService search,
            StreamPublisher publisher,
            StringRedisTemplate redis,
            OcrProperties properties) {
        return new UploadFinalizedConsumer(
                documents, ocr, search, publisher, redis, properties.consumerGroup());
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean(name = "ocrStreamListenerContainer")
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> ocrStreamListenerContainer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redis,
            UploadFinalizedConsumer consumer,
            OcrProperties properties) {

        // Make sure the stream + consumer group exist before we subscribe.
        StreamConsumerSupport.ensureGroupExists(
                redis, StreamNames.UPLOAD_FINALIZED, properties.consumerGroup());

        @SuppressWarnings({"unchecked", "rawtypes"})
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                (StreamMessageListenerContainerOptions) StreamMessageListenerContainerOptions
                        .builder()
                        .batchSize(properties.batchSize())
                        .pollTimeout(properties.pollTimeout())
                        // Lettuce will occasionally close the shared connection mid-XREADGROUP.
                        // Default behavior cancels the subscription on the first error — we
                        // want to log + keep polling instead.
                        .errorHandler(t -> log.warn("OCR stream poll error (continuing): {}", t.toString()))
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        String consumerName = properties.resolvedConsumerName();
        Consumer consumerRef = Consumer.from(properties.consumerGroup(), consumerName);
        StreamOffset<String> offset = StreamOffset.create(
                StreamNames.UPLOAD_FINALIZED, ReadOffset.lastConsumed());

        Subscription subscription = container.receive(consumerRef, offset, consumer);
        log.info("Registered OCR stream subscription group={} consumer={} stream={} subscription={}",
                properties.consumerGroup(), consumerName, StreamNames.UPLOAD_FINALIZED, subscription);
        container.start();
        return container;
    }

    /**
     * No-op bean — the listener container handles its own start/stop via
     * SmartLifecycle. The {@link PostConstruct} below just makes lifecycle
     * issues visible during boot.
     */
    @Bean
    OcrLifecycleLogger ocrLifecycleLogger(OcrProperties properties) {
        return new OcrLifecycleLogger(properties);
    }

    static class OcrLifecycleLogger {
        private final OcrProperties properties;
        OcrLifecycleLogger(OcrProperties properties) { this.properties = properties; }

        @PostConstruct void onInit() {
            log.info("OCR pipeline initialized: group={} pollTimeout={} batchSize={} extractedTextLimit={}",
                    properties.consumerGroup(),
                    properties.pollTimeout(),
                    properties.batchSize(),
                    properties.extractedTextLimitBytes());
        }

        @PreDestroy void onShutdown() {
            log.info("OCR pipeline shutting down (group={})", properties.consumerGroup());
        }
    }
}
