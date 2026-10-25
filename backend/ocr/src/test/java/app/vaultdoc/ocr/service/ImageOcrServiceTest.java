package app.vaultdoc.ocr.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.OcrStatus;
import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;
import app.vaultdoc.ocr.support.FakeTesseractRunner;
import app.vaultdoc.ocr.support.MutableClock;
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
        AtomicInteger n = new AtomicInteger();
        var runner = FakeTesseractRunner.answering((path, langs) -> (n.incrementAndGet() == 1 ? "one" : "two") + "\f");

        OcrResult result = service(runner).extract(key, "image/tiff");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.text()).isEqualTo("one\n\ntwo");
        assertThat(result.failureReason()).isNull();
    }

    @Test
    void multi_page_tiff_frames_are_ocrd_one_at_a_time_each_under_the_pixel_cap() {
        // 3 frames of 100×100 = 30 000 px in total, cap 15 000: each frame is
        // fine on its own, and each goes to tesseract as its own bounded call.
        var frame = TestDocuments.textImage("x", 100, 100);
        when(storage.download(key)).thenReturn(TestDocuments.multiPageTiff(List.of(frame, frame, frame)));
        var runner = FakeTesseractRunner.returning("frame text");
        var service = new ImageOcrService(storage, runner, languages(runner), 25_000_000, 15_000, 2_000_000, tempRoot);

        OcrResult result = service.extract(key, "image/tiff");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(runner.calls()).hasSize(3).allSatisfy(call -> {
            assertThat(call.existed()).isTrue();
            assertThat((long) call.width() * call.height()).isLessThanOrEqualTo(15_000);
        });
    }

    @Test
    void multi_page_tiff_over_frame_cap_is_bounded_and_reported() {
        var frame = TestDocuments.textImage("x", 40, 20);
        when(storage.download(key)).thenReturn(TestDocuments.multiPageTiff(java.util.Collections.nCopies(25, frame)));
        var runner = FakeTesseractRunner.returning("fax page");

        OcrResult result = service(runner, 20).extract(key, "image/tiff");

        assertThat(runner.calls()).hasSize(20);
        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(result.pageCount()).isEqualTo(25);
        assertThat(result.failureReason()).contains("first 20 of 25 pages");
    }

    @Test
    void multi_page_tiff_frame_failure_keeps_other_frames() {
        var frame = TestDocuments.textImage("x", 60, 30);
        when(storage.download(key)).thenReturn(TestDocuments.multiPageTiff(List.of(frame, frame, frame)));
        AtomicInteger n = new AtomicInteger();
        var runner = FakeTesseractRunner.answering((path, langs) -> {
            int i = n.incrementAndGet();
            if (i == 2) throw new TesseractTimeoutException("tesseract timed out after 60s");
            return "frame " + i;
        });

        OcrResult result = service(runner).extract(key, "image/tiff");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(result.text()).isEqualTo("frame 1\n\nframe 3");
        assertThat(result.failureReason()).contains("page 2").contains("timed out");
    }

    @Test
    void multi_page_tiff_all_frames_failing_is_failed() {
        var frame = TestDocuments.textImage("x", 60, 30);
        when(storage.download(key)).thenReturn(TestDocuments.multiPageTiff(List.of(frame, frame)));

        OcrResult result = service(FakeTesseractRunner.throwing(new TesseractTimeoutException("tesseract timed out after 60s")))
                .extract(key, "image/tiff");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("pages 1, 2").contains("timed out");
    }

    @Test
    void multi_page_tiff_time_budget_stops_later_frames() {
        var frame = TestDocuments.textImage("x", 60, 30);
        when(storage.download(key)).thenReturn(TestDocuments.multiPageTiff(List.of(frame, frame, frame)));
        var clock = new MutableClock();
        var runner = FakeTesseractRunner.answering((path, langs) -> {
            clock.advance(Duration.ofMinutes(6));
            return "frame";
        });
        var service = new ImageOcrService(storage, runner, languages(runner), 25_000_000, 50_000_000, 2_000_000,
                tempRoot, 20, Duration.ofMinutes(10), clock);

        OcrResult result = service.extract(key, "image/tiff");

        assertThat(runner.calls()).hasSize(2);
        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(result.failureReason()).contains("time budget").contains("page 3");
    }

    @Test
    void unusable_temp_root_is_failed_with_reason() throws IOException {
        Path notADirectory = Files.writeString(tempRoot.resolve("file"), "x");
        when(storage.download(key)).thenReturn(png);
        var runner = FakeTesseractRunner.returning("never");
        var service = new ImageOcrService(storage, runner, languages(runner), 25_000_000, 50_000_000, 2_000_000, notADirectory);

        OcrResult result = service.extract(key, "image/png");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("Could not stage image for OCR");
        assertThat(runner.calls()).isEmpty();
    }

    @Test
    void exif_orientation_is_applied_before_ocr() {
        // Phone portrait shot: 300×100 pixels stored landscape, EXIF says "rotate 90° clockwise".
        BufferedImage stored = TestDocuments.textImage("PASSPORT", 300, 100);
        markTopLeft(stored);
        when(storage.download(key)).thenReturn(TestDocuments.jpegWithExifOrientation(stored, 6));
        AtomicReference<BufferedImage> seen = new AtomicReference<>();
        var runner = FakeTesseractRunner.answering((path, langs) -> {
            seen.set(read(path));
            return "PASSPORT";
        });

        OcrResult result = service(runner).extract(key, "image/jpeg");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(runner.calls()).singleElement().satisfies(call -> {
            assertThat(call.width()).isEqualTo(100);
            assertThat(call.height()).isEqualTo(300);
        });
        // Rotating 90° clockwise moves the stored top-left corner to the top-right.
        assertThat(isDark(seen.get().getRGB(100 - 6, 5))).isTrue();
        assertThat(isDark(seen.get().getRGB(5, 5))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"1, 300, 100", "2, 300, 100", "3, 300, 100", "4, 300, 100", "5, 100, 300", "6, 100, 300", "7, 100, 300", "8, 100, 300"})
    void every_exif_orientation_yields_upright_dimensions(int orientation, int width, int height) {
        when(storage.download(key)).thenReturn(TestDocuments.jpegWithExifOrientation(TestDocuments.textImage("ID", 300, 100), orientation));
        var runner = FakeTesseractRunner.returning("ID");

        service(runner).extract(key, "image/jpeg");

        assertThat(runner.calls()).singleElement().satisfies(call -> {
            assertThat(call.width()).isEqualTo(width);
            assertThat(call.height()).isEqualTo(height);
        });
    }

    @Test
    void upright_jpeg_is_passed_through_byte_exact() {
        byte[] jpeg = TestDocuments.jpegWithExifOrientation(TestDocuments.textImage("ID", 300, 100), 1);
        when(storage.download(key)).thenReturn(jpeg);
        var runner = FakeTesseractRunner.returning("ID");

        service(runner).extract(key, "image/jpeg");

        assertThat(runner.calls()).singleElement().satisfies(call -> assertThat(call.sizeBytes()).isEqualTo(jpeg.length));
    }

    @Test
    void malformed_exif_is_ignored() {
        byte[] jpeg = TestDocuments.jpegWithExifOrientation(TestDocuments.textImage("ID", 300, 100), 6);
        jpeg[12] = 'X';   // break the TIFF byte-order mark inside the EXIF block
        when(storage.download(key)).thenReturn(jpeg);
        var runner = FakeTesseractRunner.returning("ID");

        OcrResult result = service(runner).extract(key, "image/jpeg");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(runner.calls()).singleElement().satisfies(call -> assertThat(call.sizeBytes()).isEqualTo(jpeg.length));
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

    private ImageOcrService service(TesseractRunner runner, int maxFrames) {
        return new ImageOcrService(storage, runner, languages(runner), 25_000_000, 50_000_000, 2_000_000, tempRoot,
                maxFrames, Duration.ofMinutes(10), null);
    }

    private static void markTopLeft(BufferedImage image) {
        var g = image.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, 12, 12);
        g.dispose();
    }

    private static boolean isDark(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r + g + b < 3 * 80;
    }

    private static BufferedImage read(Path path) {
        try {
            return javax.imageio.ImageIO.read(path.toFile());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
