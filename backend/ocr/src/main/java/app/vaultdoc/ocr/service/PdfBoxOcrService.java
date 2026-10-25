package app.vaultdoc.ocr.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;

/**
 * Extracts text from PDFs via PDFBox. With a {@link ScannedPdfFallback}
 * configured (Phase 1.4), each page whose text layer is (near-)empty is
 * rendered and OCR'd with Tesseract — so scanned PDFs and hybrid PDFs
 * (digital pages + scanned annexures) become searchable too.
 *
 * <p>A page that can't be OCR'd costs only that page: the document is
 * {@code DONE} with the text that was extracted and a note naming what is
 * missing. It is {@code FAILED} only when no page produced any text.
 */
public class PdfBoxOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxOcrService.class);

    /** Text-layer pages extracted per document when no explicit cap is configured. */
    public static final int DEFAULT_MAX_TEXT_PAGES = 2_000;

    private final ObjectStorage storage;
    private final int extractedTextLimitBytes;
    private final ScannedPdfFallback fallback;
    private final int maxTextPages;

    /** PDFBox only — image-only pages yield empty text (Phase 1.3 behaviour). */
    public PdfBoxOcrService(ObjectStorage storage, int extractedTextLimitBytes) {
        this(storage, extractedTextLimitBytes, null);
    }

    /** @param fallback scanned-page OCR; null disables it */
    public PdfBoxOcrService(ObjectStorage storage, int extractedTextLimitBytes, ScannedPdfFallback fallback) {
        this(storage, extractedTextLimitBytes, fallback, DEFAULT_MAX_TEXT_PAGES);
    }

    /** @param maxTextPages only the first this-many pages are read at all (text layer and OCR) */
    public PdfBoxOcrService(ObjectStorage storage, int extractedTextLimitBytes, ScannedPdfFallback fallback, int maxTextPages) {
        this.storage = storage;
        this.extractedTextLimitBytes = extractedTextLimitBytes;
        this.fallback = fallback;
        this.maxTextPages = maxTextPages > 0 ? maxTextPages : DEFAULT_MAX_TEXT_PAGES;
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
            List<String> notes = new ArrayList<>();
            List<String> pages = PageTextExtractor.extract(doc, maxTextPages);
            if (pages.size() < pageCount) {
                notes.add("Only the first %d of %d pages were indexed; later pages are not searchable"
                        .formatted(pages.size(), pageCount));
            }
            String text = fallback == null ? join(pages) : withScannedPages(doc, pages, notes, key);
            if (!notes.isEmpty()) {
                log.warn("PDF only partly searchable key={} : {}", key.value(), notes);
            }
            return OcrResult.done(OcrText.capToLimit(text, extractedTextLimitBytes), pageCount, String.join("; ", notes));
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
     * Scanned pages (within the fallback's page cap and time budget) are
     * replaced by their OCR text when that yields more visible characters.
     * Per-page failures are collected, never thrown, unless nothing readable
     * is left at all.
     */
    private String withScannedPages(PDDocument doc, List<String> pages, List<String> notes, StorageKey key)
            throws ScannedPageFailure {
        List<String> out = new ArrayList<>(pages);
        PageOcrTally tally;
        try (ScannedPdfFallback.Session session = fallback.open(doc)) {
            tally = session.tally();
            for (int i = 0; i < out.size(); i++) {
                String pageText = out.get(i);
                if (!fallback.isScanned(pageText)) continue;
                String ocr = session.ocrIfAllowed(i);
                if (ocr != null && OcrText.visibleChars(ocr) >= OcrText.visibleChars(pageText)) {
                    out.set(i, ocr);
                }
            }
        }
        String text = join(out);
        if (text.isEmpty() && tally.anyFailed()) {
            throw new ScannedPageFailure(tally.failureReason("Scanned-PDF OCR"));
        }
        if (tally.anyFailed()) {
            log.warn("Scanned-PDF OCR failed on some pages key={} : {}", key.value(), tally.failureReason("OCR"));
        }
        notes.addAll(tally.notes("scanned pages"));
        return text;
    }

    private static String join(List<String> pages) {
        StringBuilder out = new StringBuilder();
        for (String page : pages) {
            if (page.isEmpty()) continue;
            if (!out.isEmpty()) out.append("\n\n");
            out.append(page);
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
