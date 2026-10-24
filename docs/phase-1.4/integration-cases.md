# Phase 1.4 — Integration (OCR × Tags): case matrix

Cases that only exist once both slices are merged on `phase-1.4`: the OCR
consumer (now running tesseract on images) and the tag synchronizer both write
the same Elasticsearch document, and the list/viewer render OCR status and tag
chips side by side. Slice-level matrices: [`ocr-cases.md`](./ocr-cases.md),
[`tags-cases.md`](./tags-cases.md).

| # | Case | Expected | Test |
|---|---|---|---|
| I1 | OCR re-index and tag update hit the same ES doc concurrently | both apply; neither writer fails with `version_conflict_engine_exception` (ES `retry_on_conflict`) | `ElasticsearchSearchServiceTagIT.concurrentReindexAndTagUpdate_bothApply` |
| I2 | PDF tagged right after finalize, OCR finishes later | OCR word + tag filter find the doc (was the merged-tree failure) | `TagFlowIT.ocrReindex_preservesTags` |
| I3 | PNG tagged while tesseract runs `[bin]` | `DONE`; detail carries the tag; search by an image-only word filtered by the tag hits, hit carries `tagIds` | `OcrPipelineIT.tagged_image_keeps_tags_after_tesseract_reindex` |
| I4 | HEIC tagged, OCR `SKIPPED` | skip reason and tags both on the detail; tag-filtered filename search hits | `OcrPipelineIT.skipped_image_keeps_tags_and_reason` |
| I5 | `DocumentDetail` schema carries `ocrFailureReason` (with SKIPPED wording) and `tags` | both fields present in `openapi/vaultdoc.yaml` | merge resolution — covered by I3/I4 JSON assertions |
| W1 | List row with tags and a FAILED badge | chip and labelled OCR badge render together | `document-list.component.spec.ts › a tagged row shows its chips alongside the OCR badge` |
| W2 | Viewer FAILED with tags on the detail | tag bar chip + reason in the alert region | `document-viewer.component.spec.ts › shows the detail tags next to the FAILED reason` |

W1/W2 are written but **not executed**: node/npm were not runnable in the
integration environment (see BUILD_LOG Phase 1.4). The existing OCR web specs
were updated to stub the tag dependencies the merged components now inject
(`TagsService`, `selectedTagIds`, `tagError`, `documents`).
