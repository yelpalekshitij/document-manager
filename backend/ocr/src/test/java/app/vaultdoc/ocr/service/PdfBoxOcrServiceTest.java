package app.vaultdoc.ocr.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.OcrStatus;
import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;
import app.vaultdoc.ocr.support.FakeTesseractRunner;
import app.vaultdoc.ocr.support.TestDocuments;
import app.vaultdoc.ocr.tesseract.TesseractLanguageResolver;
import app.vaultdoc.ocr.tesseract.TesseractTimeoutException;

class PdfBoxOcrServiceTest {

    @TempDir Path tempRoot;

    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final PdfBoxOcrService service = new PdfBoxOcrService(storage, 2_000_000);

    private final StorageKey key = StorageKey.forDocument(UUID.randomUUID(), UUID.randomUUID(), "doc.pdf");

    // ── Phase 1.3 behaviour (PDFBox only) ───────────────────────────────────

    @Test
    void extracts_text_from_a_text_bearing_pdf() throws Exception {
        byte[] pdf = newPdfWithText("Tax refund summary for 2024");
        when(storage.download(key)).thenReturn(pdf);

        OcrResult result = service.extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(result.text()).contains("Tax refund summary for 2024");
        assertThat(result.pageCount()).isEqualTo(1);
    }

    @Test
    void skips_non_pdf_mime_types() {
        OcrResult result = service.extract(key, "image/jpeg");
        assertThat(result.status()).isEqualTo(OcrStatus.SKIPPED);
        assertThat(result.text()).isNull();
    }

    @Test
    void fails_on_malformed_bytes() {
        when(storage.download(key)).thenReturn("not a pdf at all".getBytes());

        OcrResult result = service.extract(key, "application/pdf");
        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).isNotBlank();
    }

    @Test
    void caps_extracted_text_at_byte_limit() throws Exception {
        // 5000-char repeating string → ~5000 bytes (ASCII). Cap at 100 bytes.
        PdfBoxOcrService small = new PdfBoxOcrService(storage, 100);
        byte[] pdf = newPdfWithText("a".repeat(5000));
        when(storage.download(key)).thenReturn(pdf);

        OcrResult result = small.extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(result.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(100);
    }

    @Test
    void encrypted_pdf_fails_with_reason() {
        when(storage.download(key)).thenReturn(TestDocuments.encryptedPdf("secret", "pw"));

        OcrResult result = withFallback(FakeTesseractRunner.returning("never")).extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).containsIgnoringCase("password");
    }

    @Test
    void storage_failure_is_failed() {
        when(storage.download(key)).thenThrow(new IllegalStateException("S3 down"));

        OcrResult result = withFallback(FakeTesseractRunner.returning("never")).extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("Could not download object").contains("S3 down");
    }

    // ── Phase 1.4 scanned-page fallback ─────────────────────────────────────

    @Test
    void text_pdf_does_not_invoke_fallback() {
        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of("Rental agreement between the landlord and tenant")));
        var runner = FakeTesseractRunner.returning("never");

        OcrResult result = withFallback(runner).extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(result.text()).contains("Rental agreement");
        assertThat(runner.calls()).isEmpty();
        assertThat(runner.listCalls()).isZero();
    }

    @Test
    void image_only_pdf_falls_back_to_tesseract() {
        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of(TestDocuments.textImage("SCAN", 300, 100))));
        var runner = FakeTesseractRunner.returning("SCANNED PASSPORT PAGE\f");

        OcrResult result = withFallback(runner).extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(result.text()).isEqualTo("SCANNED PASSPORT PAGE");
        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(runner.calls()).singleElement().satisfies(call -> {
            assertThat(call.existed()).isTrue();
            assertThat(call.languages()).containsExactly("eng");
        });
    }

    @Test
    void near_empty_text_triggers_fallback() {
        // "Scanned by X" stamps / page numbers are a classic thin text layer over a scan.
        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of("p. 1")));
        var runner = FakeTesseractRunner.returning("Full OCR text of the scanned page");

        OcrResult result = withFallback(runner).extract(key, "application/pdf");

        assertThat(runner.calls()).hasSize(1);
        assertThat(result.text()).isEqualTo("Full OCR text of the scanned page");
    }

    @Test
    void text_at_threshold_does_not_trigger_fallback() {
        var runner = FakeTesseractRunner.returning("OCR");

        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of("a".repeat(25))));
        withFallback(runner).extract(key, "application/pdf");
        assertThat(runner.calls()).isEmpty();

        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of("a".repeat(24))));
        withFallback(runner).extract(key, "application/pdf");
        assertThat(runner.calls()).hasSize(1);
    }

    @Test
    void hybrid_pdf_ocrs_only_scanned_pages() {
        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of(
                "Digital cover page with plenty of real text",
                TestDocuments.textImage("SCAN", 300, 100))));
        var runner = FakeTesseractRunner.returning("scanned annexure");

        OcrResult result = withFallback(runner).extract(key, "application/pdf");

        assertThat(runner.calls()).hasSize(1);
        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.text()).contains("Digital cover page").contains("scanned annexure");
        assertThat(result.text().indexOf("Digital cover page")).isLessThan(result.text().indexOf("scanned annexure"));
    }

    @Test
    void page_cap_limits_rendered_pages() {
        var page = TestDocuments.textImage("SCAN", 200, 80);
        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of(page, page, page)));
        AtomicInteger n = new AtomicInteger();
        var runner = FakeTesseractRunner.answering((path, langs) -> "page-" + n.incrementAndGet());

        OcrResult result = fallbackService(runner, 2, 300, 50_000_000).extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(runner.calls()).hasSize(2);
        assertThat(result.pageCount()).isEqualTo(3);
        assertThat(result.text()).contains("page-1").contains("page-2").doesNotContain("page-3");
    }

    @Test
    void render_dpi_is_honoured() {
        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of(TestDocuments.textImage("SCAN", 200, 80))));
        var runner = FakeTesseractRunner.returning("text");

        fallbackService(runner, 20, 100, 50_000_000).extract(key, "application/pdf");

        // US Letter is 8.5in × 11in → 850 × 1100 px at 100 DPI (±1 for PDFBox's float scale + floor).
        assertThat(runner.calls()).singleElement().satisfies(call -> {
            assertThat(call.width()).isCloseTo(850, within(1));
            assertThat(call.height()).isCloseTo(1100, within(1));
        });
    }

    @Test
    void oversized_render_fails_before_rendering() {
        // 200in × 200in page: 60000 × 60000 px at 300 DPI — rendering would OOM.
        when(storage.download(key)).thenReturn(TestDocuments.blankPdf(14_400, 14_400));
        var runner = FakeTesseractRunner.returning("never");

        OcrResult result = withFallback(runner).extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("page 1").contains("megapixel");
        assertThat(runner.calls()).isEmpty();
    }

    @Test
    void fallback_failure_is_surfaced() {
        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of(TestDocuments.textImage("SCAN", 200, 80))));
        var runner = FakeTesseractRunner.throwing(new TesseractTimeoutException("tesseract timed out after 60s"));

        OcrResult result = withFallback(runner).extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("Scanned-PDF OCR failed on page 1").contains("timed out after 60s");
    }

    @Test
    void fallback_without_installed_language_is_failed() {
        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of(TestDocuments.textImage("SCAN", 200, 80))));
        var runner = FakeTesseractRunner.returning("never").withInstalled("osd");

        OcrResult result = withFallback(runner).extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.FAILED);
        assertThat(result.failureReason()).contains("No configured OCR language");
    }

    @Test
    void fallback_disabled_keeps_pdfbox_result() {
        when(storage.download(key)).thenReturn(TestDocuments.pdf(List.of(TestDocuments.textImage("SCAN", 200, 80))));

        OcrResult result = service.extract(key, "application/pdf");

        assertThat(result.status()).isEqualTo(OcrStatus.DONE);
        assertThat(result.text()).isBlank();
        assertThat(result.pageCount()).isEqualTo(1);
    }

    @Test
    void fallback_cleans_temp_files() throws IOException {
        var scan = TestDocuments.pdf(List.of(TestDocuments.textImage("SCAN", 200, 80)));
        when(storage.download(key)).thenReturn(scan);

        withFallback(FakeTesseractRunner.returning("ok")).extract(key, "application/pdf");
        withFallback(FakeTesseractRunner.throwing(new TesseractTimeoutException("timed out"))).extract(key, "application/pdf");
        withFallback(FakeTesseractRunner.throwing(new IllegalStateException("bug"))).extract(key, "application/pdf");

        try (Stream<Path> left = Files.list(tempRoot)) {
            assertThat(left).isEmpty();
        }
    }

    private PdfBoxOcrService withFallback(FakeTesseractRunner runner) {
        return fallbackService(runner, 20, 300, 50_000_000);
    }

    private PdfBoxOcrService fallbackService(FakeTesseractRunner runner, int maxPages, int dpi, long maxPixels) {
        var languages = new TesseractLanguageResolver(runner, List.of("eng", "hin"), msg -> {});
        var fallback = new ScannedPdfFallback(runner, languages, maxPages, dpi, maxPixels, 25, tempRoot);
        return new PdfBoxOcrService(storage, 2_000_000, fallback);
    }

    private static byte[] newPdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
