package app.vaultdoc.ocr.service;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;

/**
 * Extracts text from PDFs via PDFBox. With a {@link ScannedPdfFallback}
 * configured (Phase 1.4), each page whose text layer is (near-)empty is
 * rendered and OCR'd with Tesseract — so scanned PDFs and hybrid PDFs
 * (digital pages + scanned annexures) become searchable too.
 */
public class PdfBoxOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxOcrService.class);

    private final ObjectStorage storage;
    private final int extractedTextLimitBytes;
    private final ScannedPdfFallback fallback;

    /** PDFBox only — image-only pages yield empty text (Phase 1.3 behaviour). */
    public PdfBoxOcrService(ObjectStorage storage, int extractedTextLimitBytes) {
        this(storage, extractedTextLimitBytes, null);
    }

    /** @param fallback scanned-page OCR; null disables it */
    public PdfBoxOcrService(ObjectStorage storage, int extractedTextLimitBytes, ScannedPdfFallback fallback) {
        this.storage = storage;
        this.extractedTextLimitBytes = extractedTextLimitBytes;
        this.fallback = fallback;
    }

    @Override
    public OcrResult extract(StorageKey key, String mimeType) {
        if (!"application/pdf".equalsIgnoreCase(mimeType)) {
            return OcrResult.skipped("MIME type " + mimeType + " is not a PDF");
        }
        byte[] bytes;
        try {
            bytes = storage.download(key);
        } catch (RuntimeException e) {
            log.warn("Failed to download object for OCR key={} : {}", key.value(), e.toString());
            return OcrResult.failed("Could not download object: " + OcrText.summarize(e));
        }

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int pageCount = doc.getNumberOfPages();
            String text = fallback == null
                    ? new PDFTextStripper().getText(doc)
                    : extractPageByPage(doc, key);
            return OcrResult.done(OcrText.capToLimit(text, extractedTextLimitBytes), pageCount);
        } catch (ScannedPageFailure e) {
            log.warn("Scanned-PDF OCR failed key={} : {}", key.value(), e.getMessage());
            return OcrResult.failed(e.getMessage());
        } catch (InvalidPasswordException e) {
            return OcrResult.failed("PDF is password-protected; remove the password and re-upload to make it searchable");
        } catch (IOException e) {
            log.warn("PDF text extraction failed key={} : {}", key.value(), e.toString());
            return OcrResult.failed("PDFBox could not parse the document: " + OcrText.summarize(e));
        } catch (RuntimeException e) {
            log.warn("Unexpected error during PDF text extraction key={} : {}", key.value(), e.toString());
            return OcrResult.failed("Unexpected extraction error: " + OcrText.summarize(e));
        }
    }

    /**
     * Text layer page by page; scanned pages (up to the fallback's page cap)
     * are replaced by their OCR text when that yields more visible characters.
     */
    private String extractPageByPage(PDDocument doc, StorageKey key) throws IOException, ScannedPageFailure {
        PDFTextStripper stripper = new PDFTextStripper();
        PDFRenderer renderer = null;
        TempWorkspace workspace = null;
        List<String> langs = null;
        int ocrPages = 0;
        int skippedOverCap = 0;
        StringBuilder out = new StringBuilder();
        try {
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(doc).strip();

                if (fallback.isScanned(pageText)) {
                    if (ocrPages >= fallback.maxPages()) {
                        skippedOverCap++;
                    } else {
                        try {
                            if (langs == null) langs = fallback.languages();
                            if (renderer == null) renderer = new PDFRenderer(doc);
                            if (workspace == null) workspace = fallback.openWorkspace();
                            String ocr = fallback.ocrPage(renderer, doc.getPage(page - 1), page - 1, langs, workspace);
                            ocrPages++;
                            if (OcrText.visibleChars(ocr) >= OcrText.visibleChars(pageText)) pageText = ocr;
                        } catch (ScannedPdfFallback.PageOcrException e) {
                            throw new ScannedPageFailure("Scanned-PDF OCR failed on page " + page + ": " + e.getMessage());
                        }
                    }
                }
                if (!pageText.isEmpty()) {
                    if (!out.isEmpty()) out.append("\n\n");
                    out.append(pageText);
                }
            }
        } finally {
            if (workspace != null) workspace.close();
        }
        if (skippedOverCap > 0) {
            log.warn("Scanned-PDF OCR page cap reached key={} ocrPages={} notOcrd={}",
                    key.value(), ocrPages, skippedOverCap);
        }
        return out.toString();
    }

    /** Carries a user-readable reason out of the per-page loop. */
    private static final class ScannedPageFailure extends Exception {
        ScannedPageFailure(String message) {
            super(message);
        }
    }
}
