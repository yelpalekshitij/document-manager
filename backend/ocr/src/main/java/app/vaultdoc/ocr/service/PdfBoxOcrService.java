package app.vaultdoc.ocr.service;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;

/**
 * Extracts text from text-bearing PDFs via PDFBox. Image-only PDFs come back
 * with mostly-empty text — that's fine for Phase 1.3; rasterized OCR via
 * Tesseract lands in Phase 1.4.
 */
public class PdfBoxOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxOcrService.class);

    private final ObjectStorage storage;
    private final int extractedTextLimitBytes;

    public PdfBoxOcrService(ObjectStorage storage, int extractedTextLimitBytes) {
        this.storage = storage;
        this.extractedTextLimitBytes = extractedTextLimitBytes;
    }

    @Override
    public OcrResult extract(StorageKey key, String mimeType) {
        if (!"application/pdf".equalsIgnoreCase(mimeType)) {
            return OcrResult.skipped("MIME type " + mimeType + " not supported in Phase 1.3 (PDFBox only)");
        }
        byte[] bytes;
        try {
            bytes = storage.download(key);
        } catch (RuntimeException e) {
            log.warn("Failed to download object for OCR key={} : {}", key.value(), e.toString());
            return OcrResult.failed("Could not download object: " + summarize(e));
        }

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(doc);
            int pageCount = doc.getNumberOfPages();
            String capped = capToLimit(text);
            return OcrResult.done(capped, pageCount);
        } catch (IOException e) {
            log.warn("PDF text extraction failed key={} : {}", key.value(), e.toString());
            return OcrResult.failed("PDFBox could not parse the document: " + summarize(e));
        } catch (RuntimeException e) {
            log.warn("Unexpected error during PDF text extraction key={} : {}", key.value(), e.toString());
            return OcrResult.failed("Unexpected extraction error: " + summarize(e));
        }
    }

    private String capToLimit(String text) {
        if (text == null) return null;
        byte[] utf8 = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (utf8.length <= extractedTextLimitBytes) return text;
        // Truncate on a safe UTF-8 boundary — drop trailing partial codepoints.
        int end = Math.min(extractedTextLimitBytes, utf8.length);
        while (end > 0 && (utf8[end - 1] & 0xC0) == 0x80) end--;
        return new String(utf8, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String summarize(Throwable t) {
        String message = t.getMessage();
        if (message == null) return t.getClass().getSimpleName();
        if (message.length() > 200) return message.substring(0, 200) + "…";
        return message;
    }
}
