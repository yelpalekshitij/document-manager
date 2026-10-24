package app.vaultdoc.ocr.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.OcrStatus;
import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;
import app.vaultdoc.ocr.support.FakeTesseractRunner;
import app.vaultdoc.ocr.support.TestDocuments;
import app.vaultdoc.ocr.tesseract.TesseractException;
import app.vaultdoc.ocr.tesseract.TesseractLanguageResolver;
import app.vaultdoc.ocr.tesseract.TesseractRunner;
import app.vaultdoc.ocr.tesseract.TesseractTimeoutException;
import app.vaultdoc.ocr.tesseract.TesseractUnavailableException;

class ImageOcrServiceTest {

    @TempDir Path tempRoot;

    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final StorageKey key = StorageKey.forDocument(UUID.randomUUID(), UUID.randomUUID(), "scan.png");
    private final byte[] png = TestDocuments.png(TestDocuments.textImage("PASSPORT", 400, 120));

    @Test
    void png_happy_path_returns_runner_text() {
        when(storage.download(key)).thenReturn(png);
        var runner = FakeTesseractRunner.returning("PASSPORT No. K1234567");

        OcrResult result = service(runner).extract(key, "image/png");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(result.text()).isEqualTo("PASSPORT No. K1234567");
        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.failureReason()).isNull();
        // The engine saw the exact bytes, as a real file, with the installed subset of eng+hin.
        assertThat(runner.calls()).singleElement().satisfies(call -> {
            assertThat(call.existed()).isTrue();
            assertThat(call.sizeBytes()).isEqualTo(png.length);
            assertThat(call.width()).isEqualTo(400);
            assertThat(call.languages()).containsExactly("eng");
        });
    }

    @Test
    void jpeg_and_tiff_are_accepted() {
        var image = TestDocuments.textImage("RENT", 300, 100);
        var runner = FakeTesseractRunner.returning("RENT");

        when(storage.download(key)).thenReturn(TestDocuments.jpeg(image));
        assertThat(service(runner).extract(key, "image/jpeg").status()).isEqualTo(OcrStatus.DONE);

        when(storage.download(key)).thenReturn(TestDocuments.tiff(image));
        OcrResult tiff = service(runner).extract(key, "image/tiff");
        assertThat(tiff.status()).isEqualTo(OcrStatus.DONE);
        assertThat(tiff.pageCount()).isEqualTo(1);
    }

    @Test
    void multi_page_tiff_reports_frame_count() {
        when(storage.download(key)).thenReturn(TestDocuments.multiPageTiff(List.of(
                TestDocuments.textImage("one", 200, 80), TestDocuments.textImage("two", 200, 80))));

        OcrResult result = service(FakeTesseractRunner.returning("one\f\ntwo\f")).extract(key, "image/tiff");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.text()).isEqualTo("one\n\ntwo");
    }

    @Test
    void webp_is_probed_from_header_and_ocrd() {
        byte[] webp = TestDocuments.webpVp8x(800, 600);
        when(storage.download(key)).thenReturn(webp);
        var runner = FakeTesseractRunner.returning("webp text");

        OcrResult result = service(runner).extract(key, "image/webp");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(runner.calls()).singleElement().satisfies(call -> {
            assertThat(call.sizeBytes()).isEqualTo(webp.length);
            assertThat(call.image().getFileName().toString()).endsWith(".webp");
        });
    }

    @Test
    void strips_form_feed_and_trims() {
        when(storage.download(key)).thenReturn(png);

        OcrResult result = service(FakeTesseractRunner.returning("  line one\r\nline two \n\f")).extract(key, "image/png");

        assertThat(result.text()).isEqualTo("line one\r\nline two");
    }

    @Test
    void blank_image_is_done_with_empty_text() {
        when(storage.download(key)).thenReturn(png);

        OcrResult result = service(FakeTesseractRunner.returning(" \n\f")).extract(key, "image/png");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(result.text()).isEmpty();
    }

    @Test
    void unicode_text_is_preserved() {
        when(storage.download(key)).thenReturn(png);
        String hindi = "भारत गणराज्य — पासपोर्ट";

        OcrResult result = service(FakeTesseractRunner.returning(hindi)).extract(key, "image/png");

        assertThat(result.text()).isEqualTo(hindi);
    }

    @Test
    void text_is_capped_on_utf8_boundary() {
        when(storage.download(key)).thenReturn(png);
        // Each Devanagari letter is 3 UTF-8 bytes; a 10-byte cap must land on 9.
        var service = new ImageOcrService(storage, FakeTesseractRunner.returning("भारतभारत"), languages(FakeTesseractRunner.returning("")),
                1_000_000, 1_000_000, 10, tempRoot);

        OcrResult result = service.extract(key, "image/png");

        assertThat(result.text()).isEqualTo("भार");
        assertThat(result.text().getBytes(StandardCharsets.UTF_8)).hasSize(9);
    }

    @Test
    void oversized_bytes_fail_without_running_tesseract() {
        when(storage.download(key)).thenReturn(png);
        var runner = FakeTesseractRunner.returning("never");
        var service = new ImageOcrService(storage, runner, languages(runner), png.length - 1, 50_000_000, 2_000_000, tempRoot);

        OcrResult result = service.extract(key, "image/png");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("exceeds").contains("bytes");
        assertThat(runner.calls()).isEmpty();
    }

    @Test
    void oversized_pixels_fail_without_decoding() {
        // Header claims 20000 x 20000 = 400 MP — never decoded, just probed.
        when(storage.download(key)).thenReturn(TestDocuments.webpVp8x(20_000, 20_000));
        var runner = FakeTesseractRunner.returning("never");
        var service = new ImageOcrService(storage, runner, languages(runner), 25_000_000, 50_000_000, 2_000_000, tempRoot);

        OcrResult result = service.extract(key, "image/webp");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("20000×20000").contains("megapixel");
        assertThat(runner.calls()).isEmpty();
    }

    @Test
    void corrupt_image_fails_with_reason() {
        when(storage.download(key)).thenReturn(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16});
        var runner = FakeTesseractRunner.returning("never");

        OcrResult result = service(runner).extract(key, "image/jpeg");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("could not be decoded");
        assertThat(runner.calls()).isEmpty();
    }

    @Test
    void empty_object_fails() {
        when(storage.download(key)).thenReturn(new byte[0]);

        OcrResult result = service(FakeTesseractRunner.returning("never")).extract(key, "image/png");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("empty");
    }

    @Test
    void storage_failure_is_failed() {
        when(storage.download(key)).thenThrow(new IllegalStateException("S3 unreachable"));

        OcrResult result = service(FakeTesseractRunner.returning("never")).extract(key, "image/png");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("Could not download object").contains("S3 unreachable");
    }

    @Test
    void timeout_is_failed_with_reason() {
        when(storage.download(key)).thenReturn(png);

        OcrResult result = service(FakeTesseractRunner.throwing(new TesseractTimeoutException("tesseract timed out after 60s")))
                .extract(key, "image/png");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("timed out after 60s");
    }

    @Test
    void missing_binary_is_failed_with_reason() {
        when(storage.download(key)).thenReturn(png);
        var unavailable = new TesseractUnavailableException("tesseract not available at 'tesseract'", null);
        var runner = FakeTesseractRunner.throwing(unavailable).withListFailure(unavailable);

        OcrResult result = service(runner).extract(key, "image/png");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("tesseract not available");
    }

    @Test
    void non_zero_exit_is_failed_with_stderr() {
        when(storage.download(key)).thenReturn(png);

        OcrResult result = service(FakeTesseractRunner.throwing(
                new TesseractException("tesseract failed with exit code 1: Error in pixReadStream")))
                .extract(key, "image/png");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("exit code 1").contains("pixReadStream");
    }

    @Test
    void no_installed_language_is_failed() {
        when(storage.download(key)).thenReturn(png);
        var runner = FakeTesseractRunner.returning("never").withInstalled("osd");

        OcrResult result = service(runner).extract(key, "image/png");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("No configured OCR language").contains("eng+hin");
        assertThat(runner.calls()).isEmpty();
    }

    @Test
    void temp_files_are_removed_on_success() throws IOException {
        when(storage.download(key)).thenReturn(png);

        service(FakeTesseractRunner.returning("ok")).extract(key, "image/png");

        assertThat(listTempRoot()).isEmpty();
    }

    @Test
    void temp_files_are_removed_on_failure_and_timeout() throws IOException {
        when(storage.download(key)).thenReturn(png);

        service(FakeTesseractRunner.throwing(new TesseractTimeoutException("timed out"))).extract(key, "image/png");
        service(FakeTesseractRunner.throwing(new TesseractException("exit 1"))).extract(key, "image/png");
        service(FakeTesseractRunner.throwing(new IllegalStateException("bug"))).extract(key, "image/png");

        assertThat(listTempRoot()).isEmpty();
    }

    @Test
    void non_image_mime_is_skipped() {
        OcrResult result = service(FakeTesseractRunner.returning("never")).extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.SKIPPED);
    }

    private ImageOcrService service(TesseractRunner runner) {
        return new ImageOcrService(storage, runner, languages(runner), 25_000_000, 50_000_000, 2_000_000, tempRoot);
    }

    private static TesseractLanguageResolver languages(TesseractRunner runner) {
        return new TesseractLanguageResolver(runner, List.of("eng", "hin"), msg -> {});
    }

    private List<Path> listTempRoot() throws IOException {
        try (Stream<Path> files = Files.list(tempRoot)) {
            return files.toList();
        }
    }
}
