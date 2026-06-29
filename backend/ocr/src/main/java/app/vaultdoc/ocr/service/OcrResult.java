package app.vaultdoc.ocr.service;

import app.vaultdoc.catalog.domain.OcrStatus;

/**
 * Outcome of a single OCR pass. {@code text} is null unless {@code status==DONE}.
 * {@code pageCount} is null when the extractor can't determine it (e.g. non-PDF).
 */
public record OcrResult(OcrStatus status, String text, Integer pageCount, String failureReason) {

    public static OcrResult done(String text, int pageCount) {
        return new OcrResult(OcrStatus.DONE, text, pageCount, null);
    }

    public static OcrResult skipped(String reason) {
        return new OcrResult(OcrStatus.SKIPPED, null, null, reason);
    }

    public static OcrResult failed(String reason) {
        return new OcrResult(OcrStatus.FAILED, null, null, reason);
    }
}
