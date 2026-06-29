package app.vaultdoc.ocr.service;

import app.vaultdoc.core.storage.StorageKey;

/**
 * Pluggable text extractor. The default (Phase 1.3) wraps PDFBox for digital
 * PDFs and skips everything else. Phase 1.4 will add Tesseract for images;
 * Phase 2 will layer AWS Textract / Google Doc AI fallbacks.
 */
public interface OcrService {

    OcrResult extract(StorageKey key, String mimeType);
}
