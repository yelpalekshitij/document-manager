package app.vaultdoc.ocr.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.vaultdoc.core.storage.StorageKey;

/**
 * Default {@link OcrService}: normalises the MIME type and dispatches to the
 * engine that owns it — PDFBox (+ scanned-page Tesseract fallback) for PDFs,
 * Tesseract for raster images. Anything else is {@code SKIPPED} with a reason
 * the UI can show.
 *
 * <p>This is also the last line of defence for "never silently fail": an
 * engine that throws or returns nothing is turned into {@code FAILED} here
 * rather than escaping to the stream consumer.
 */
public class RoutingOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(RoutingOcrService.class);

    static final String PDF = "application/pdf";
    static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/tiff", "image/webp");

    /** Non-standard spellings browsers, scanners and old mail clients still send. */
    static final Map<String, String> ALIASES = Map.of(
            "image/jpg", "image/jpeg",
            "image/pjpeg", "image/jpeg",
            "image/x-png", "image/png",
            "image/tif", "image/tiff",
            "image/x-tiff", "image/tiff");

    private final OcrService pdf;
    private final OcrService image;

    public RoutingOcrService(OcrService pdf, OcrService image) {
        this.pdf = pdf;
        this.image = image;
    }

    @Override
    public OcrResult extract(StorageKey key, String mimeType) {
        String mime = normalise(mimeType);
        if (mime == null) {
            return OcrResult.skipped("No MIME type recorded for this document; OCR skipped");
        }
        if (PDF.equals(mime)) {
            return delegate(pdf, key, mime);
        }
        if (IMAGES.contains(mime)) {
            return delegate(image, key, mime);
        }
        return OcrResult.skipped("OCR is not supported for " + mime + " (supported: PDF, JPEG, PNG, TIFF, WebP)");
    }

    private OcrResult delegate(OcrService engine, StorageKey key, String mime) {
        try {
            OcrResult result = engine.extract(key, mime);
            if (result == null) {
                log.error("OCR engine {} returned no result for key={}", engine.getClass().getSimpleName(), key.value());
                return OcrResult.failed("OCR engine returned no result");
            }
            return result;
        } catch (RuntimeException e) {
            log.error("OCR engine {} crashed for key={}", engine.getClass().getSimpleName(), key.value(), e);
            return OcrResult.failed("Unexpected OCR error: " + OcrText.summarize(e));
        } catch (OutOfMemoryError e) {
            // The engine's allocation failed, not the JVM at large: the oversized
            // buffer is already unreachable. Record it so the document doesn't sit
            // in PROCESSING and the stream entry is acked instead of redelivered.
            log.error("OCR engine {} ran out of memory for key={}", engine.getClass().getSimpleName(), key.value(), e);
            return OcrResult.failed("The document needs more memory to OCR than the server allows");
        }
    }

    /** Lower-cases, trims, drops parameters ({@code ; charset=…}) and maps {@link #ALIASES}. */
    static String normalise(String mimeType) {
        if (mimeType == null) return null;
        String mime = mimeType;
        int semicolon = mime.indexOf(';');
        if (semicolon >= 0) mime = mime.substring(0, semicolon);
        mime = mime.strip().toLowerCase(Locale.ROOT);
        if (mime.isEmpty()) return null;
        return ALIASES.getOrDefault(mime, mime);
    }
}
