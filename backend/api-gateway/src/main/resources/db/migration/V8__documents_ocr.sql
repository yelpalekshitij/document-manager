-- Phase 1.3: per-document OCR columns.
-- The OCR pipeline (PDFBox in 1.3, Tesseract in 1.4) writes back to these
-- fields after a stream consumer processes upload-finalized events.

ALTER TABLE documents
    ADD COLUMN ocr_status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN ocr_text           TEXT,
    ADD COLUMN ocr_page_count     INTEGER,
    ADD COLUMN ocr_completed_at   TIMESTAMPTZ,
    ADD COLUMN ocr_failure_reason TEXT;

ALTER TABLE documents
    ADD CONSTRAINT documents_ocr_status_check
        CHECK (ocr_status IN ('PENDING','PROCESSING','DONE','FAILED','SKIPPED'));

-- Cheap to surface in-flight or failed work without scanning the whole table.
CREATE INDEX documents_ocr_status_pending_idx
    ON documents (ocr_status, uploaded_at)
    WHERE ocr_status IN ('PENDING','PROCESSING','FAILED');

COMMENT ON COLUMN documents.ocr_text IS 'Extracted full-text. Capped at vaultdoc.ocr.extracted-text-limit-bytes; ES has the full version.';
COMMENT ON COLUMN documents.ocr_failure_reason IS 'Short reason string when ocr_status = FAILED; null otherwise.';
