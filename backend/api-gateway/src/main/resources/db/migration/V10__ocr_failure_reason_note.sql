-- Phase 1.4: ocr_failure_reason is no longer FAILED-only. SKIPPED carries the
-- skip reason, and DONE may carry a note on what is not searchable (page cap,
-- OCR time budget, a page that could not be OCR'd). Comment-only change.
--
-- Rollback: re-run the V8 COMMENT statement (no data or schema change to undo).

COMMENT ON COLUMN documents.ocr_failure_reason IS
    'User-visible OCR note: why FAILED or SKIPPED, or on DONE which part is not searchable; null on a complete DONE.';
