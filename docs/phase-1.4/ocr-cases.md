# Phase 1.4 — OCR slice: case matrix

Tesseract OCR for image uploads + scanned-PDF fallback. Every row below has a
test that covers it; the **Test** column names the class and method. Tests that
need a real `tesseract` binary are gated with JUnit `Assumptions` and are marked
`[bin]` — everything else runs anywhere via a fake `TesseractRunner`.

Status vocabulary: the catalog enum is `OcrStatus.{PENDING, PROCESSING, DONE, FAILED, SKIPPED}`
(`DONE` is the "completed" state). `Document.ocrFailureReason` carries the reason
for both `FAILED` and `SKIPPED`.

Backend test classes live in `backend/ocr/src/test/java/app/vaultdoc/ocr/...`
unless the class is under `api-gateway`. Web specs live next to the component.

## 1. Routing / strategy (`RoutingOcrService`)

| # | Case | Expected | Test |
|---|---|---|---|
| R1 | `application/pdf` | dispatched to the PDF engine | `RoutingOcrServiceTest.routes_pdf_to_pdf_engine` |
| R2 | `image/png`, `image/jpeg`, `image/tiff`, `image/webp` | dispatched to the image engine | `RoutingOcrServiceTest.routes_supported_images_to_image_engine` (parameterized) |
| R3 | MIME casing / whitespace / parameters (`IMAGE/PNG`, ` image/png ; q=1`) | normalised then dispatched | `RoutingOcrServiceTest.normalises_mime_casing_whitespace_and_parameters` |
| R4 | `image/jpg` alias (non-standard but common) | treated as JPEG | `RoutingOcrServiceTest.routes_supported_images_to_image_engine` |
| R5 | Unsupported type (`image/heic`, DOCX, `text/plain`) | `SKIPPED` + reason naming the type; no engine invoked | `RoutingOcrServiceTest.unsupported_mime_is_skipped_with_reason` |
| R6 | `null` / blank MIME | `SKIPPED` + "no MIME type" reason | `RoutingOcrServiceTest.missing_mime_is_skipped_with_reason` |
| R7 | Engine throws an unexpected `RuntimeException` | `FAILED` + reason (never propagates, never silent) | `RoutingOcrServiceTest.engine_crash_is_surfaced_as_failed` |
| R8 | Engine returns `null` (programming error) | `FAILED` + reason | `RoutingOcrServiceTest.engine_returning_null_is_failed` |

## 2. Image OCR (`ImageOcrService`)

| # | Case | Expected | Test |
|---|---|---|---|
| I1 | PNG happy path | `DONE`, runner text, `pageCount=1` | `ImageOcrServiceTest.png_happy_path_returns_runner_text` |
| I2 | JPEG happy path | `DONE` | `ImageOcrServiceTest.jpeg_and_tiff_are_accepted` |
| I3 | TIFF happy path (single page) | `DONE`, `pageCount=1` | `ImageOcrServiceTest.jpeg_and_tiff_are_accepted` |
| I4 | Multi-page TIFF | `DONE`, `pageCount` = frame count | `ImageOcrServiceTest.multi_page_tiff_reports_frame_count` |
| I5 | WebP (no JDK ImageIO reader) | dimensions read from the RIFF header, handed to tesseract (leptonica reads WebP) | `ImageOcrServiceTest.webp_is_probed_from_header_and_ocrd` + `ImageDimensionsTest.*webp*` |
| I6 | Tesseract output with trailing form-feed / CRLF | form-feed stripped, text trimmed | `ImageOcrServiceTest.strips_form_feed_and_trims` |
| I7 | Tesseract returns only whitespace (blank scan) | `DONE` with empty text (honest: nothing to read) | `ImageOcrServiceTest.blank_image_is_done_with_empty_text` |
| I8 | Unicode (Devanagari) text from runner | preserved byte-exact | `ImageOcrServiceTest.unicode_text_is_preserved` |
| I9 | Text larger than `extractedTextLimitBytes` | capped on a UTF-8 boundary | `ImageOcrServiceTest.text_is_capped_on_utf8_boundary` |
| I10 | Bytes > `maxImageBytes` | `FAILED` "exceeds … bytes"; runner never called | `ImageOcrServiceTest.oversized_bytes_fail_without_running_tesseract` |
| I11 | Pixels > `maxImagePixels` (header only — no full decode, no OOM) | `FAILED` "… megapixels"; runner never called | `ImageOcrServiceTest.oversized_pixels_fail_without_decoding` |
| I12 | Corrupt / truncated image bytes | `FAILED` "could not be decoded" | `ImageOcrServiceTest.corrupt_image_fails_with_reason` |
| I13 | Empty (0-byte) object | `FAILED` "empty" | `ImageOcrServiceTest.empty_object_fails` |
| I14 | Object-store download throws (store down) | `FAILED` "Could not download object" | `ImageOcrServiceTest.storage_failure_is_failed` |
| I15 | Runner times out | `FAILED` "timed out after …" | `ImageOcrServiceTest.timeout_is_failed_with_reason` |
| I16 | Tesseract binary missing | `FAILED` "tesseract not available …" | `ImageOcrServiceTest.missing_binary_is_failed_with_reason` |
| I17 | Tesseract exits non-zero | `FAILED` with stderr summary | `ImageOcrServiceTest.non_zero_exit_is_failed_with_stderr` |
| I18 | None of the configured languages installed | `FAILED` "no configured OCR language installed" | `ImageOcrServiceTest.no_installed_language_is_failed` |
| I19 | Temp files removed on success | temp root empty afterwards | `ImageOcrServiceTest.temp_files_are_removed_on_success` |
| I20 | Temp files removed on failure / timeout | temp root empty afterwards | `ImageOcrServiceTest.temp_files_are_removed_on_failure_and_timeout` |
| I21 | Runner receives a file with the original bytes and the resolved languages | path exists during the call; langs `eng` | `ImageOcrServiceTest.png_happy_path_returns_runner_text` |
| I22 | Called with a non-image MIME directly | `SKIPPED` (defensive) | `ImageOcrServiceTest.non_image_mime_is_skipped` |

## 3. Scanned-PDF fallback (`PdfBoxOcrService`)

| # | Case | Expected | Test |
|---|---|---|---|
| P1 | Digital PDF with text | `DONE` via PDFBox; runner never called | `PdfBoxOcrServiceTest.text_pdf_does_not_invoke_fallback` |
| P2 | Image-only PDF | pages rendered → runner → `DONE` with OCR text, `pageCount`=PDF pages | `PdfBoxOcrServiceTest.image_only_pdf_falls_back_to_tesseract` |
| P3 | Page with near-empty text (< `pdfMinTextCharsPerPage` non-whitespace chars, e.g. a "p. 1" stamp) | treated as scanned → fallback; OCR text wins when longer | `PdfBoxOcrServiceTest.near_empty_text_triggers_fallback` |
| P4 | Text exactly at threshold (25) vs one below (24) | 25 → no fallback, 24 → fallback (boundary) | `PdfBoxOcrServiceTest.text_at_threshold_does_not_trigger_fallback` |
| P16 | Hybrid PDF: digital page + scanned page | only the scanned page OCR'd; text in page order | `PdfBoxOcrServiceTest.hybrid_pdf_ocrs_only_scanned_pages` |
| P17 | Scanned page but no configured language installed | `FAILED` "No configured OCR language" | `PdfBoxOcrServiceTest.fallback_without_installed_language_is_failed` |
| P5 | Page cap: 3-page scan, `pdfOcrMaxPages=2` | only 2 pages rendered/OCR'd; `pageCount=3` | `PdfBoxOcrServiceTest.page_cap_limits_rendered_pages` |
| P6 | DPI honoured | rendered PNG width = 8.5in × dpi | `PdfBoxOcrServiceTest.render_dpi_is_honoured` |
| P7 | Rendered page would exceed `maxImagePixels` | `FAILED` before rendering (no OOM) | `PdfBoxOcrServiceTest.oversized_render_fails_before_rendering` |
| P8 | Fallback runner times out | `FAILED` "Scanned-PDF OCR failed: … timed out" | `PdfBoxOcrServiceTest.fallback_failure_is_surfaced` |
| P9 | Fallback disabled (`pdfOcrFallbackEnabled=false` / no runner) | PDFBox result kept as `DONE` (Phase 1.3 behaviour) | `PdfBoxOcrServiceTest.fallback_disabled_keeps_pdfbox_result` |
| P10 | Temp files removed on success / failure | temp root empty | `PdfBoxOcrServiceTest.fallback_cleans_temp_files` |
| P11 | Malformed PDF bytes | `FAILED` (existing) | `PdfBoxOcrServiceTest.fails_on_malformed_bytes` |
| P12 | Password-protected PDF | `FAILED` with reason | `PdfBoxOcrServiceTest.encrypted_pdf_fails_with_reason` |
| P13 | Text cap respected (existing) | ≤ limit bytes | `PdfBoxOcrServiceTest.caps_extracted_text_at_byte_limit` |
| P14 | Store download fails | `FAILED` | `PdfBoxOcrServiceTest.storage_failure_is_failed` |
| P15 | Non-PDF MIME passed directly | `SKIPPED` (existing) | `PdfBoxOcrServiceTest.skips_non_pdf_mime_types` |

## 4. Tesseract CLI runner (`CliTesseractRunner`) — fake `/bin/sh` binaries

| # | Case | Expected | Test |
|---|---|---|---|
| C1 | Success: stdout captured as UTF-8 | returns text | `CliTesseractRunnerTest.returns_stdout_text` |
| C2 | Command line: `<img> stdout -l eng+hin [--tessdata-dir d]`, no shell | args recorded by fake | `CliTesseractRunnerTest.passes_languages_and_tessdata_dir_as_separate_args` |
| C3 | Non-zero exit | `TesseractException` with exit code + stderr tail | `CliTesseractRunnerTest.non_zero_exit_raises_with_stderr` |
| C4 | Timeout | `TesseractTimeoutException`; process (and children) killed | `CliTesseractRunnerTest.timeout_kills_process_and_raises` |
| C5 | Binary missing / not executable | `TesseractUnavailableException` | `CliTesseractRunnerTest.missing_binary_raises_unavailable` |
| C6 | Large stdout (> pipe buffer) | no deadlock (stdout redirected to file) | `CliTesseractRunnerTest.large_output_does_not_deadlock` |
| C7 | `--list-langs` parsing (header line, blanks, `osd`) | language set | `CliTesseractRunnerTest.lists_languages_skipping_header` |
| C8 | Runner's own scratch files removed | work dir clean afterwards | `CliTesseractRunnerTest.removes_its_scratch_files` |
| C9 | Interrupted while waiting | process killed, interrupt flag restored, exception raised | `CliTesseractRunnerTest.interrupt_kills_process` |

## 5. Language configuration (`OcrProperties`, `TesseractLanguageResolver`)

| # | Case | Expected | Test |
|---|---|---|---|
| L1 | Default languages | `eng+hin` (CLAUDE.md English + Hindi) | `OcrPropertiesTest.defaults_are_sane` |
| L2 | Whitespace, `+`/`,` separators, duplicates | normalised, de-duplicated, order kept | `OcrPropertiesTest.languages_are_parsed_trimmed_and_deduplicated` |
| L3 | Invalid tokens (`-c`, `../x`, `eng;rm`) | dropped (argument-injection guard) | `OcrPropertiesTest.invalid_language_tokens_are_dropped` |
| L4a | `null` / empty / blank (indistinguishable from unset, e.g. `OCR_LANGUAGES=`) | default `eng+hin` | `OcrPropertiesTest.blank_languages_mean_the_default` |
| L4b | All tokens invalid (`+++`, `-l`, `../../etc`) | falls back to `eng` | `OcrPropertiesTest.all_invalid_languages_fall_back_to_eng` |
| L5 | `hin` configured but not installed | resolves to `eng` + one WARN naming the missing pack | `TesseractLanguageResolverTest.missing_language_degrades_with_warning` |
| L6 | All installed | all kept, no warning | `TesseractLanguageResolverTest.all_installed_languages_are_kept` |
| L7 | None installed | empty → caller FAILs (I18) | `TesseractLanguageResolverTest.none_installed_returns_empty` |
| L8 | `--list-langs` fails (binary missing) | exception propagates → FAILED; retried next call (not cached) | `TesseractLanguageResolverTest.listing_failure_is_not_cached` |
| L9 | Resolution cached after success | `--list-langs` invoked once | `TesseractLanguageResolverTest.successful_resolution_is_cached` |
| L10 | Language codes are case-sensitive (`ENG` ≠ `eng`) | `ENG` warned + dropped | `TesseractLanguageResolverTest.language_match_is_case_sensitive` |

## 6. Properties bounds (`OcrProperties`)

| # | Case | Expected | Test |
|---|---|---|---|
| O1 | Defaults for timeout, DPI, page cap, byte/pixel caps, min-chars, binary | documented defaults | `OcrPropertiesTest.defaults_are_sane` |
| O2 | DPI outside 72..600 (72 and 600 accepted) / page cap ≤0 / negative caps / zero timeout / blank paths | fall back to defaults | `OcrPropertiesTest.out_of_range_values_fall_back_to_defaults` |
| O4 | Fallback explicitly disabled | `false` respected (not defaulted) | `OcrPropertiesTest.fallback_can_be_switched_off_explicitly` |
| O3 | Existing 1.3 properties still default | unchanged | `OcrPropertiesTest.defaults_are_sane` |

## 7. Image header probing (`ImageDimensions`)

| # | Case | Expected | Test |
|---|---|---|---|
| D1 | PNG/JPEG/TIFF via ImageIO header (no raster decode) | width/height/frames | `ImageDimensionsTest.reads_png_jpeg_tiff_headers` |
| D1b | Multi-frame TIFF | largest frame + frame count | `ImageDimensionsTest.multi_frame_tiff_reports_largest_frame_and_count` |
| D2 | WebP VP8X / VP8 / VP8L headers | width/height | `ImageDimensionsTest.reads_webp_vp8x_vp8_vp8l_headers` |
| D3 | Truncated / garbage / RIFF-but-not-WebP | `ImageDecodeException` | `ImageDimensionsTest.garbage_is_rejected` |

## 8. Consumer (`UploadFinalizedConsumer`)

| # | Case | Expected | Test |
|---|---|---|---|
| U1 | Result persisted (status, text, pages, reason) + re-indexed + XACK | — | `UploadFinalizedConsumerTest.persists_result_reindexes_and_acks` |
| U2 | `FAILED` result → reason persisted, no text in ES | — | `UploadFinalizedConsumerTest.failed_result_persists_reason_and_indexes_without_text` |
| U3 | OCR service throws → `FAILED` + DLQ + XACK | — | `UploadFinalizedConsumerTest.unexpected_exception_marks_failed_and_dead_letters` |
| U4 | Unknown document → ack only, no DLQ | — | `UploadFinalizedConsumerTest.unknown_document_is_acked` |
| U5 | Replay of the same event (idempotency) | same row/ES doc overwritten, both acked | `UploadFinalizedConsumerTest.replay_is_idempotent` |
| U6 | Malformed envelope (no documentId) | DLQ + ack, no status write | `UploadFinalizedConsumerTest.malformed_event_is_dead_lettered` |

## 9. Real binary `[bin]` (`TesseractCliIT`, ocr module)

| # | Case | Expected | Test |
|---|---|---|---|
| T1 | Java2D-rendered PNG with known words | text contains the words | `TesseractCliIT.reads_text_from_generated_png` |
| T2 | Java2D image wrapped in an image-only PDF | fallback OCRs it | `TesseractCliIT.scanned_pdf_fallback_reads_text` |
| T3 | Default `eng+hin` on a host with only `eng` | degrades to `eng`, still `DONE` | `TesseractCliIT.reads_text_from_generated_png` (default languages) |
| T4 | Real `--list-langs` output parses | contains `eng` | `TesseractCliIT.lists_installed_languages` |

## 10. End-to-end (`OcrPipelineIT`, api-gateway, Testcontainers PG/Redis/ES/MinIO)

| # | Case | Expected | Test |
|---|---|---|---|
| E1 | PDF upload → `DONE` + searchable (existing) | — | `OcrPipelineIT.pdf_upload_triggers_ocr_and_text_is_searchable` |
| E2 | Corrupt PDF → `FAILED` + filename search works (existing) | — | `OcrPipelineIT.corrupted_pdf_marks_ocr_failed_but_filename_search_still_works` |
| E3 `[bin]` | PNG upload → `DONE`, text searchable | — | `OcrPipelineIT.png_upload_is_ocrd_and_text_is_searchable` |
| E4 `[bin]` | Image-only PDF → `DONE` via fallback, searchable | — | `OcrPipelineIT.scanned_pdf_is_ocrd_via_fallback` |
| E5 | Corrupt JPEG → `FAILED` + reason in `DocumentDetail` (no binary needed) | — | `OcrPipelineIT.corrupt_image_marks_ocr_failed_with_reason` |
| E6 | Unsupported MIME (HEIC) → `SKIPPED` + reason | — | `OcrPipelineIT.unsupported_image_type_is_skipped_with_reason` |
| E7 | Cross-tenant: another user can't see the OCR'd doc or find it by OCR text | 404 / 0 hits | `OcrPipelineIT.ocr_text_is_tenant_scoped` |

## 11. Wiring (`OcrAutoConfiguration`)

| # | Case | Expected | Test |
|---|---|---|---|
| A1 | Default `OcrService` bean is the MIME router with PDF + image engines | — | `OcrAutoConfigurationTest.default_ocr_service_is_router` |
| A2 | Fallback disabled by config → PDF engine gets no runner | — | `OcrAutoConfigurationTest.fallback_can_be_disabled` |
| A3 | Boot-time language probe with tesseract missing | WARN logged, startup never fails (text PDFs still work) | `OcrAutoConfigurationTest.startup_probe_is_never_fatal` |
| A4 | Runner/resolver built from properties (`languages`, `tessdata-dir`) | — | `OcrAutoConfigurationTest.runner_and_resolver_follow_properties` |

## 12. Web UI (Angular specs)

> Written test-first, **not yet executed**: `npm install` was not permitted in the
> environment this slice was built in, so Karma could not run. Run `cd web && npm test`
> before merging.

| # | Case | Expected | Test |
|---|---|---|---|
| W1 | Badge icon per status (DONE/PROCESSING/PENDING/SKIPPED/FAILED/unknown) | ✓ ⏳ ⏳ · ✗ — | `ocr-status.spec.ts › ocrIcon` |
| W2 | Badge has an accessible label (not only `title`) | `role="img"` + `aria-label` = status label | `document-list.component.spec.ts › OCR badge is labelled for screen readers` |
| W3 | SKIPPED copy no longer says "Tesseract lands in 1.4" | "OCR not available for this file type" | `ocr-status.spec.ts › ocrLabel` |
| W4 | Viewer FAILED shows server reason in an alert region; panel opens itself | `role="alert"` + reason text, `<details open>` | `document-viewer.component.spec.ts › shows the failure reason for FAILED in an alert region, panel open` |
| W5 | Viewer FAILED without reason | generic fallback copy | `document-viewer.component.spec.ts › falls back to generic copy when FAILED has no reason` |
| W6 | Viewer SKIPPED shows reason | reason text | `document-viewer.component.spec.ts › shows the skip reason for SKIPPED` |
| W7 | Viewer DONE with empty text (blank scan) | "No text found" copy (not stuck on "Loading") | `document-viewer.component.spec.ts › DONE with empty text shows no-text copy` |
| W8 | Viewer loading / processing states | loading copy, in-progress copy | `document-viewer.component.spec.ts › shows loading then in-progress copy` |
| W9 | Viewer detail fetch error | alert shown instead of "Loading…" forever | `document-viewer.component.spec.ts › surfaces a fetch error instead of loading forever` |
| W10 | Viewer DONE with (Unicode) text | text + page count | `document-viewer.component.spec.ts › DONE with text renders it` |
| W11 | List empty state | "No documents yet" | `document-list.component.spec.ts › shows the empty state when there are no documents` |

## 13. Runtime image / docs

| # | Case | Expected | Verified by |
|---|---|---|---|
| X1 | Runtime image ships `tesseract-ocr` + `eng` + `hin` | `tesseract --list-langs` in the image lists both | `backend/api-gateway/Dockerfile` — manual `docker build` + `docker run … --list-langs` (see BUILD_LOG 1.4) |
| X2 | Infra README documents the OCR knobs + local install | — | `infra/README.md` review |

## 14. User-visible failure reasons carry no internals

| # | Case | Expected | Verified by |
|---|---|---|---|
| S1 | Tesseract exits non-zero with stderr | reason says the exit code; stderr only in logs | `ImageOcrServiceTest › non_zero_exit_is_failed_without_leaking_stderr`, `CliTesseractRunnerTest › non_zero_exit_raises_with_stderr` |
| S2 | Tesseract binary missing at a configured path | reason "tesseract not available on this server"; path only in logs | `ImageOcrServiceTest › missing_binary_is_failed_with_reason`, `CliTesseractRunnerTest › missing_binary_raises_unavailable` |
| S3 | Temp workspace unusable | reason names the failure, not the host path | `ImageOcrServiceTest › unusable_temp_root_is_failed_with_reason` |
| S4 | Unexpected exception in the consumer | reason is a generic internal-error note; detail kept in the log and DLQ entry | `UploadFinalizedConsumerTest › unexpected_exception_marks_failed_and_dead_letters` |

## Out of scope / follow-ups

- Mobile (Flutter) is not installed here: the generated Dart client needs no change (no OpenAPI change in this slice); copy updates for the mobile OCR badge are a mobile follow-up.
- Language auto-detection per document (CLAUDE.md §14) — this slice uses a configured language set.
- Concurrency cap on simultaneous tesseract processes — the OCR consumer is single-threaded per container today.
