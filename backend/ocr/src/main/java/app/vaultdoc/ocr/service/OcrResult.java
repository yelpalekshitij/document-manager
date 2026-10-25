package app.vaultdoc.ocr.service;

import app.vaultdoc.catalog.domain.OcrStatus;

/**
 * Outcome of a single OCR pass. {@code text} is null unless {@code status==DONE}.
 * {@code pageCount} is null when the extractor can't determine it (e.g. non-PDF).
 *
 * <p>{@code failureReason} is the user-visible explanation: why the document
 * was {@code FAILED} or {@code SKIPPED}, or — on {@code DONE} — what was
 * <em>not</em> made searchable (pages over a cap, a page that could not be
 * OCR'd). Null on a complete {@code DONE}.
 */
public record OcrResult(OcrStatus status, String text, Integer pageCount, String failureReason) {

    public static OcrResult done(String text, int pageCount) {
        return new OcrResult(OcrStatus.DONE, text, pageCount, null);
    }

    /** {@code DONE}, but only partly searchable; {@code note} says which part is missing and why. */
    public static OcrResult done(String text, int pageCount, String note) {
        return new OcrResult(OcrStatus.DONE, text, pageCount, note == null || note.isBlank() ? null : note);
    }

    public static OcrResult skipped(String reason) {
        return new OcrResult(OcrStatus.SKIPPED, null, null, reason);
    }

    public static OcrResult failed(String reason) {
        return new OcrResult(OcrStatus.FAILED, null, null, reason);
    }
}
