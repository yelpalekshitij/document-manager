package app.vaultdoc.ocr.tesseract;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.OcrStatus;
import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;
import app.vaultdoc.ocr.config.OcrProperties;
import app.vaultdoc.ocr.service.ImageOcrService;
import app.vaultdoc.ocr.service.OcrResult;
import app.vaultdoc.ocr.service.PdfBoxOcrService;
import app.vaultdoc.ocr.service.ScannedPdfFallback;
import app.vaultdoc.ocr.support.TesseractBinary;
import app.vaultdoc.ocr.support.TestDocuments;

/**
 * Runs the real tesseract binary. Skipped (not failed) when it isn't
 * installed — every behaviour that doesn't need the engine itself is covered
 * by the fake-runner unit tests.
 */
class TesseractCliIT {

    @TempDir Path tempRoot;

    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final StorageKey key = StorageKey.forDocument(UUID.randomUUID(), UUID.randomUUID(), "scan");
    private CliTesseractRunner runner;
    private TesseractLanguageResolver languages;

    @BeforeEach
    void requireTesseract() {
        assumeTrue(TesseractBinary.available(), "tesseract binary not installed — skipping real-engine test");
        runner = new CliTesseractRunner(TesseractBinary.path().orElseThrow(), null, Duration.ofSeconds(60), tempRoot);
        // Default eng+hin: on a host with only eng this exercises graceful degradation.
        languages = new TesseractLanguageResolver(runner, OcrProperties.defaults().languageList());
    }

    @Test
    void reads_text_from_generated_png() {
        when(storage.download(key)).thenReturn(TestDocuments.png(TestDocuments.textImage("INVOICE 4821", 1200, 200)));
        var service = new ImageOcrService(storage, runner, languages, 25_000_000, 50_000_000, 2_000_000, tempRoot);

        OcrResult result = service.extract(key, "image/png");

        assertThat(result.status()).as("reason: %s", result.failureReason()).isEqualTo(OcrStatus.DONE);
        assertThat(result.text()).contains("INVOICE").contains("4821");
        assertThat(languages.resolve()).contains("eng");
    }

    @Test
    void scanned_pdf_fallback_reads_text() {
        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of(TestDocuments.textImage("PASSPORT RENEWAL", 500, 100))));
        var fallback = new ScannedPdfFallback(runner, languages, 20, 300, 50_000_000, 25, tempRoot);
        var service = new PdfBoxOcrService(storage, 2_000_000, fallback);

        OcrResult result = service.extract(key, "application/pdf");

        assertThat(result.status()).as("reason: %s", result.failureReason()).isEqualTo(OcrStatus.DONE);
        assertThat(result.text()).contains("PASSPORT").contains("RENEWAL");
    }

    @Test
    void lists_installed_languages() {
        assertThat(runner.listLanguages()).contains("eng");
    }
}
