package app.vaultdoc.catalog.domain;

/**
 * Lifecycle of OCR text extraction for a single document.
 *
 * <p>Distinct from {@link DocumentStatus}, which tracks the upload itself.
 * A document is {@code DocumentStatus.READY} long before OCR finishes.
 */
public enum OcrStatus {
    /** Default after finalize; awaiting consumer pickup. */
    PENDING,
    /** Consumer has claimed the work. */
    PROCESSING,
    /** Text extracted and indexed. */
    DONE,
    /** Extraction errored. {@code Document.ocrFailureReason} carries the reason. */
    FAILED,
    /** MIME type is not eligible for OCR (e.g. an image in 1.3 before Tesseract lands). */
    SKIPPED
}
