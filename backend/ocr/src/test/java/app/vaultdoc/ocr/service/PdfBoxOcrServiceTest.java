package app.vaultdoc.ocr.service;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.OcrStatus;
import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;

class PdfBoxOcrServiceTest {

    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final PdfBoxOcrService service = new PdfBoxOcrService(storage, 2_000_000);

    private final StorageKey key = StorageKey.forDocument(UUID.randomUUID(), UUID.randomUUID(), "doc.pdf");

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
