package app.vaultdoc.ocr.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.OcrStatus;
import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;
import app.vaultdoc.ocr.consumer.PendingEntryReclaimer;
import app.vaultdoc.ocr.consumer.UploadFinalizedConsumer;
import app.vaultdoc.ocr.service.OcrResult;
import app.vaultdoc.ocr.service.OcrService;
import app.vaultdoc.ocr.service.RoutingOcrService;
import app.vaultdoc.ocr.support.FakeTesseractRunner;
import app.vaultdoc.ocr.support.TestDocuments;
import app.vaultdoc.ocr.tesseract.CliTesseractRunner;
import app.vaultdoc.ocr.tesseract.TesseractLanguageResolver;
import app.vaultdoc.ocr.tesseract.TesseractUnavailableException;

class OcrAutoConfigurationTest {

    @TempDir Path tempDir;

    private final OcrAutoConfiguration config = new OcrAutoConfiguration();
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final StorageKey key = StorageKey.forDocument(UUID.randomUUID(), UUID.randomUUID(), "x");

    @Test
    void default_ocr_service_is_router() {
        OcrProperties props = OcrProperties.defaults().withTempDir(tempDir.toString());
        var runner = FakeTesseractRunner.returning("from tesseract");
        OcrService service = config.ocrService(storage, runner, config.tesseractLanguageResolver(runner, props), props);

        assertThat(service).isInstanceOf(RoutingOcrService.class);

        when(storage.download(key)).thenReturn(TestDocuments.png(TestDocuments.textImage("X", 100, 40)));
        OcrResult image = service.extract(key, "image/png");
        assertThat(image.status()).isEqualTo(OcrStatus.DONE);
        assertThat(image.text()).isEqualTo("from tesseract");

        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of(TestDocuments.textImage("X", 100, 40))));
        OcrResult scanned = service.extract(key, "application/pdf");
        assertThat(scanned.text()).isEqualTo("from tesseract");

        assertThat(service.extract(key, "image/heic").status()).isEqualTo(OcrStatus.SKIPPED);
    }

    @Test
    void fallback_can_be_disabled() {
        OcrProperties props = OcrProperties.defaults().withPdfOcrFallbackEnabled(false);
        var runner = FakeTesseractRunner.returning("never");
        OcrService service = config.ocrService(storage, runner, config.tesseractLanguageResolver(runner, props), props);

        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of(TestDocuments.textImage("X", 100, 40))));
        OcrResult scanned = service.extract(key, "application/pdf");

        assertThat(scanned.status()).isEqualTo(OcrStatus.DONE);
        assertThat(scanned.text()).isBlank();
        assertThat(runner.calls()).isEmpty();
    }

    @Test
    void startup_probe_is_never_fatal() {
        OcrProperties props = OcrProperties.defaults();
        var missing = FakeTesseractRunner.returning("")
                .withListFailure(new TesseractUnavailableException("tesseract not available at 'tesseract'", null));
        var installed = FakeTesseractRunner.returning("").withInstalled("eng");

        // Missing binary at boot: logged, not thrown — text PDFs still work without tesseract.
        assertThatCode(() -> config.ocrLifecycleLogger(props, config.tesseractLanguageResolver(missing, props)).onInit())
                .doesNotThrowAnyException();
        assertThat(missing.listCalls()).isEqualTo(1);

        // Healthy boot resolves (and caches) languages up front.
        var resolver = config.tesseractLanguageResolver(installed, props);
        config.ocrLifecycleLogger(props, resolver).onInit();
        resolver.resolve();
        assertThat(installed.listCalls()).isEqualTo(1);
    }

    @Test
    void consumer_name_is_resolved_once_and_shared_with_the_reclaimer() {
        OcrProperties props = OcrProperties.defaults().withReclaimMinIdle(Duration.ofMinutes(45)).withMaxDeliveries(5);
        OcrAutoConfiguration.OcrConsumerName name = config.ocrConsumerName(props);

        PendingEntryReclaimer reclaimer = config.pendingEntryReclaimer(
                mock(StringRedisTemplate.class), mock(UploadFinalizedConsumer.class), props, name);
        try {
            assertThat(name.value()).startsWith("ocr-");
            assertThat(name.value()).isEqualTo(name.value());
            assertThat(reclaimer.consumerName()).isEqualTo(name.value());
            assertThat(reclaimer.minIdle()).isEqualTo(Duration.ofMinutes(45));
            assertThat(reclaimer.maxDeliveries()).isEqualTo(5);
        } finally {
            reclaimer.stop();
        }
        assertThat(config.ocrConsumerName(props.withConsumerName("ocr-fixed")).value()).isEqualTo("ocr-fixed");
    }

    @Test
    void runner_and_resolver_follow_properties() {
        OcrProperties props = OcrProperties.defaults().withLanguages("hin+eng").withTessdataDir("/opt/tessdata");

        assertThat(config.tesseractRunner(props)).isInstanceOf(CliTesseractRunner.class);
        TesseractLanguageResolver resolver = config.tesseractLanguageResolver(FakeTesseractRunner.returning(""), props);
        assertThat(resolver.configured()).containsExactly("hin", "eng");
    }
}
