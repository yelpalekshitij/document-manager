# Phase 1.4 — Tags slice: case matrix

Every behaviour the tags slice must have, one row per case, with the test that
covers it. Written **before** production code (strict TDD); the *Test* column is
kept in sync with the suite.

> **Execution status.** Every backend row is covered by a passing test
> (`cd backend && ./gradlew test`: 278 tests, 0 failures). The **web rows (W\*)
> have specs written but not executed**: `npm`/`node` were not permitted in the
> environment that built this slice, so neither the OpenAPI TS client nor the
> Karma suite could be run. First action on a machine with Node:
> `cd web && npm install && npm test && npm run build`.
>
> **Integration guards were mutation-checked** (tests that went green first try
> were proven able to fail): reverting `index()` to a full replace fails
> `EsTagIT.reindex_preservesTags` + `TagFlowIT.ocrReindex_preservesTags`;
> disabling the finalize listener fails
> `TagFlowIT.tagBeforeFinalize_tagsReachSearchAfterFinalize` +
> `SyncTest.onFinalized_pushesCurrentTags`; dropping `ON CONFLICT DO NOTHING`
> fails `TagFlowIT.concurrentAdd_sameTag_noDuplicateNoError` +
> `TagFlowIT.assignment_setAddRemove_idempotent`.

Test locations (abbreviations used below):

| Abbrev | File |
|---|---|
| `TagInputTest` | `backend/catalog/src/test/java/app/vaultdoc/catalog/service/TagInputTest.java` |
| `TagServiceImplTest` | `backend/catalog/src/test/java/app/vaultdoc/catalog/service/TagServiceImplTest.java` |
| `DocumentServiceImplTest` | `backend/catalog/src/test/java/app/vaultdoc/catalog/service/DocumentServiceImplTest.java` |
| `EsTagIT` | `backend/search/src/test/java/app/vaultdoc/search/service/ElasticsearchSearchServiceTagIT.java` |
| `TagContractTest` | `backend/api-gateway/src/test/java/app/vaultdoc/gateway/documents/TagApiContractTest.java` |
| `SyncTest` | `backend/api-gateway/src/test/java/app/vaultdoc/gateway/documents/TagIndexSynchronizerTest.java` |
| `TagFlowIT` | `backend/api-gateway/src/test/java/app/vaultdoc/gateway/documents/TagFlowIT.java` |
| `tags.store.spec` | `web/src/app/features/tags/tags.store.spec.ts` |
| `documents.store.spec` | `web/src/app/features/documents/documents.store.spec.ts` |
| `tag-filter.spec` | `web/src/app/features/tags/tag-filter.component.spec.ts` |
| `tag-manager.spec` | `web/src/app/features/tags/tag-manager.component.spec.ts` |
| `tag-chips.spec` | `web/src/app/features/tags/tag-chips.component.spec.ts` |

## Decisions the cases encode

- **No default seeded tags.** The 8 default *folders* already carry the
  opinionated taxonomy (Identity … Travel) from CLAUDE.md; seeding the same words
  as tags would duplicate it and clutter the filter. The `DefaultFolderSeeder`
  lazy-idempotent pattern stays available if product wants starter tags later.
- **No migration.** V7 already has `tags.color VARCHAR(16)` and the
  `UNIQUE (user_id, LOWER(name))` index; `document_tags` cascades on both FKs.
- **Unknown / foreign tagId in a *filter* → empty result (200)**, not 404 — it
  does not leak whether another user's tag exists, and a bookmarked filter keeps
  working after its tag is deleted. Unknown / foreign tagId in an *assignment*
  (set/add/remove) → `404 tag-not-found` (explicit reference, mirrors
  `folder-not-found` on upload).
- **Name rules:** Unicode NFC-normalised, `strip()`ped, internal whitespace runs
  collapsed to one space, control characters rejected, 1–64 *code points*
  (matches Postgres `VARCHAR(64)` semantics, so an emoji counts as 1).
- **Colour rules:** `#rrggbb` hex, stored lowercase; absent = no colour; on
  PATCH, `""` clears the colour and absent leaves it unchanged.
- **ES tag fields** (`tags` names + new `tagIds` keyword) are owned by
  `SearchService.updateTags` (partial update). `index()` became a merge-upsert
  that never writes tag fields, so the OCR consumer's re-index cannot wipe tags.
- **Assignment responses** return the document's resulting tag list (200) for
  set, add and remove, so clients update without a refetch.

## Backend — tag CRUD

| ID | Case | Level | Test |
|---|---|---|---|
| C01 | Create tag with name + colour → 201, trimmed name, lowercase colour | unit + IT | `TagServiceImplTest.create_persistsNormalisedNameAndColour`, `TagFlowIT.crud_createListRenameRecolorDelete` |
| C02 | Create tag without colour → colour null | unit | `TagServiceImplTest.create_withoutColour_storesNull` |
| C03 | Name null / empty / whitespace-only → 400 `invalid-tag` | unit + contract | `TagInputTest.name_rejectsNullEmptyAndBlank`, `TagContractTest.create_blankName_400InvalidTag` |
| C04 | Name with leading/trailing/internal whitespace runs (incl. tab/newline/NBSP) → stripped + collapsed | unit | `TagInputTest.name_stripsAndCollapsesWhitespace` |
| C05 | Name exactly 64 code points → accepted; 65 → 400 | unit | `TagInputTest.name_acceptsExactly64CodePoints`, `TagInputTest.name_rejects65CodePoints` |
| C06 | Unicode name (Devanagari, emoji) → accepted, emoji counts as one code point | unit + IT | `TagInputTest.name_countsCodePointsNotUtf16Units`, `TagFlowIT.unicodeName_roundTripsAndConflictsCaseInsensitively` |
| C07 | Composed vs decomposed accents (NFC) are the same name | unit | `TagInputTest.name_isNfcNormalised` |
| C08 | Control characters (e.g. `\u0000`, `\u0007`) → 400 | unit | `TagInputTest.name_rejectsControlCharacters` |
| C09 | Duplicate name, different case (`Tax` vs `tax`) → 409 `tag-name-conflict` | unit + IT | `TagServiceImplTest.create_caseInsensitiveDuplicate_throwsConflict`, `TagFlowIT.conflicts_createAndRenameCollisions` |
| C10 | Same name for two *different* users → both allowed | IT | `TagFlowIT.crossTenant_sameNameAllowed_andForeignTagIsNotFound` |
| C11 | Concurrent create of same name → exactly one 201, rest 409 (DB unique index is the arbiter) | unit + IT | `TagServiceImplTest.create_uniqueIndexRace_translatedToConflict`, `TagFlowIT.concurrentCreate_sameName_exactlyOneWins` |
| C12 | Colour valid `#A1B2C3` → stored `#a1b2c3` | unit | `TagInputTest.colour_acceptsHexAndLowercases` |
| C13 | Colour invalid (`red`, `#fff`, `#12345g`, `#1234567`, `javascript:`) → 400 | unit + contract | `TagInputTest.colour_rejectsNonHex`, `TagContractTest.create_badColour_400` |
| C14 | List tags → sorted case-insensitively by name with per-tag document counts | IT | `TagFlowIT.crud_createListRenameRecolorDelete`, `TagFlowIT.list_countsExcludeDeletedDocuments` |
| C15 | List counts exclude soft-deleted documents | IT | `TagFlowIT.list_countsExcludeDeletedDocuments` |
| C16 | List with zero tags → `{tags: []}` (no seeding) | IT | `TagFlowIT.list_emptyForNewUser_noSeededTags` |
| C17 | Rename → 200 new name | unit + IT | `TagServiceImplTest.update_rename_publishesReindexForTaggedDocs`, `TagFlowIT.crud_createListRenameRecolorDelete` |
| C18 | Rename to another tag's name (any case) → 409 | unit + IT | `TagServiceImplTest.update_renameCollision_throwsConflict`, `TagFlowIT.conflicts_createAndRenameCollisions` |
| C19 | Rename changing only case of own name (`tax` → `Tax`) → allowed | unit + IT | `TagServiceImplTest.update_caseOnlyRenameOfSelf_allowed`, `TagFlowIT.conflicts_createAndRenameCollisions` |
| C20 | Rename with blank name → 400 (not silently ignored) | unit | `TagServiceImplTest.update_blankName_rejected` |
| C21 | Recolor only → 200; does **not** trigger a re-index | unit | `TagServiceImplTest.update_recolorOnly_noReindexEvent` |
| C22 | PATCH colour `""` clears; absent colour unchanged | unit | `TagServiceImplTest.update_emptyColourClears_absentKeeps` |
| C23 | PATCH with empty body → 200 no-op | unit | `TagServiceImplTest.update_emptyPatch_isNoop` |
| C24 | Update / delete unknown tag → 404 `tag-not-found` | unit + contract | `TagServiceImplTest.update_unknownTag_notFound`, `TagServiceImplTest.delete_unknownTag_notFound`, `TagContractTest.delete_unknown_404ProblemDetails` |
| C25 | Delete tag → 204, detaches from all docs, re-indexes affected docs | unit + IT | `TagServiceImplTest.delete_publishesReindexForPreviouslyTaggedDocs`, `TagFlowIT.deleteTag_detachesAndReindexes` |
| C26 | Per-user tag cap (500) → 409 `tag-limit-reached` | unit | `TagServiceImplTest.create_overLimit_throws` |
| C27 | Unauthenticated → 401 | contract | `TagContractTest.unauthenticated_401` |
| C28 | Malformed JSON body → 400 problem | contract | `TagContractTest.create_malformedJson_400Problem` |
| C29 | Non-UUID path id → 400 problem (not 500) | contract | `TagContractTest.update_nonUuidPath_400Problem` |
| C30 | Missing `name` field → 400 `validation` | contract | `TagContractTest.create_missingName_400Validation` |
| C31 | `userId` smuggled in the body is ignored; token user wins | contract | `TagContractTest.create_201ReturnsTag_scopedToTokenUser` |
| C32 | PATCH response carries the live document count | unit + contract | `TagServiceImplTest.documentCount_countsLiveDocumentsOfOwnedTag`, `TagContractTest.update_200_withCount` |
| C33 | Rename race lost at the unique index → 409, no re-index | unit | `TagServiceImplTest.update_renameRace_translatedToConflict` |
| C34 | Bad name / colour end to end → 400 and nothing persisted | IT | `TagFlowIT.validation_badNameAndColour_400` |

## Backend — tag assignment

| ID | Case | Level | Test |
|---|---|---|---|
| A01 | Set tags on a doc (replace semantics) → 200 with resulting tags sorted by name | unit + IT | `TagServiceImplTest.setDocumentTags_replacesAndReturnsSorted`, `TagFlowIT.assignment_setAddRemove_idempotent` |
| A02 | Set with empty list → clears all tags | unit + IT | `TagServiceImplTest.setDocumentTags_emptyClears`, `TagFlowIT.assignment_setAddRemove_idempotent` |
| A03 | Set with duplicate ids → deduplicated | unit | `TagServiceImplTest.setDocumentTags_dedupes` |
| A04 | Set with > 50 ids → 400 | contract | `TagContractTest.setTags_tooMany_400` |
| A05 | Set with a null element → 400 | contract | `TagContractTest.setTags_nullElement_400` |
| A06 | Set / add with unknown or foreign tagId → 404 `tag-not-found`, nothing changed | unit + IT | `TagServiceImplTest.setDocumentTags_foreignTag_notFound_noWrites`, `TagFlowIT.crossTenant_sameNameAllowed_andForeignTagIsNotFound` |
| A07 | Set / add / remove on unknown or foreign document → 404 `document-not-found` | unit + IT | `TagServiceImplTest.addDocumentTag_foreignDocument_notFound`, `TagFlowIT.crossTenant_sameNameAllowed_andForeignTagIsNotFound` |
| A08 | Set / add on soft-deleted document → 404 | unit | `TagServiceImplTest.addDocumentTag_deletedDocument_notFound` |
| A09 | Add tag → 200; adding again → 200, no duplicate row, no re-index | unit + IT | `TagServiceImplTest.addDocumentTag_alreadyAttached_isNoopWithoutEvent`, `TagFlowIT.assignment_setAddRemove_idempotent` |
| A10 | Remove tag → 200; removing a not-attached (but owned) tag → 200, no re-index | unit + IT | `TagServiceImplTest.removeDocumentTag_notAttached_isNoopWithoutEvent`, `TagFlowIT.assignment_setAddRemove_idempotent` |
| A11 | Remove foreign tag → 404 `tag-not-found` | unit | `TagServiceImplTest.removeDocumentTag_foreignTag_notFound` |
| A12 | Concurrent add of same tag → no PK violation (`ON CONFLICT DO NOTHING`) | IT | `TagFlowIT.concurrentAdd_sameTag_noDuplicateNoError` |
| A13 | Set with identical set → no re-index | unit | `TagServiceImplTest.setDocumentTags_unchanged_noEvent` |
| A14 | DocumentSummary (list) and DocumentDetail (get/finalize) expose `tags` | IT | `TagFlowIT.assignment_setAddRemove_idempotent` |
| A15 | Tagging a PENDING (not yet indexed) doc is allowed and tags reach ES at finalize | unit + IT | `SyncTest.onFinalized_pushesCurrentTags`, `TagFlowIT.tagBeforeFinalize_tagsReachSearchAfterFinalize` |
| A16 | Non-UUID element / missing `tagIds` in set body → 400 | contract | `TagContractTest.setTags_nonUuidElement_400`, `TagContractTest.setTags_missingTagIds_400` |
| A17 | Remove returns the remaining tags (200, not 204) | contract | `TagContractTest.removeTag_200_returnsRemaining` |

## Backend — list filter

| ID | Case | Level | Test |
|---|---|---|---|
| L01 | `tagIds` absent / empty → no filter | unit + IT | `DocumentServiceImplTest.list_emptyTagFilter_usesUnfilteredQuery`, `TagFlowIT.listFilter_andSemantics_combinesWithFolder` |
| L02 | One tag → docs having it | IT | `TagFlowIT.listFilter_andSemantics_combinesWithFolder` |
| L03 | Two tags → AND (docs having both) | IT | `TagFlowIT.listFilter_andSemantics_combinesWithFolder` |
| L04 | Tag filter + folder filter combine | IT | `TagFlowIT.listFilter_andSemantics_combinesWithFolder` |
| L05 | Duplicate ids in filter → deduped (AND count not inflated) | unit + IT | `TagInputTest.filter_dedupes`, `TagFlowIT.listFilter_andSemantics_combinesWithFolder` |
| L06 | Unknown / foreign tagId → empty page, 200 | IT | `TagFlowIT.listFilter_unknownOrForeignTag_emptyResult` |
| L07 | > 20 filter ids → 400 | unit + contract | `TagInputTest.filter_rejectsMoreThan20`, `TagContractTest.list_tooManyTagFilters_400` |
| L08 | Non-UUID filter id → 400 problem | contract | `TagContractTest.list_nonUuidTagFilter_400Problem` |
| L09 | Soft-deleted docs never appear in filtered list | IT | `TagFlowIT.list_countsExcludeDeletedDocuments` |

## Backend — search filter + re-index

| ID | Case | Level | Test |
|---|---|---|---|
| S01 | Empty tag filter = no filter | ES IT | `EsTagIT.emptyTagFilter_isNoFilter` |
| S02 | Single tag filter | ES IT | `EsTagIT.tagFilter_singleAndAnd` |
| S03 | Multi-tag AND semantics | ES IT | `EsTagIT.tagFilter_singleAndAnd` |
| S04 | Tag filter + folder + query combine | ES IT + IT | `EsTagIT.tagFilter_combinesWithFolderAndQuery`, `TagFlowIT.searchFilter_combinesWithQuery` |
| S05 | Unknown tagId → empty result | ES IT | `EsTagIT.unknownTagFilter_emptyResult` |
| S06 | Tag filter never crosses tenants (other user's doc with same tagId value) | ES IT | `EsTagIT.tagFilter_isTenantScoped` |
| S07 | Search hits expose `tagIds` | ES IT | `EsTagIT.tagFilter_singleAndAnd` |
| S08 | `index()` re-index (e.g. OCR consumer) preserves tag fields | ES IT + IT | `EsTagIT.reindex_preservesTags`, `TagFlowIT.ocrReindex_preservesTags` |
| S09 | `index()` merge still clears nullable fields (folderId → null) | ES IT | `EsTagIT.reindex_clearsNulledFields` |
| S10 | `updateTags` on a doc not in the index is ignored (no exception) | ES IT | `EsTagIT.updateTags_missingDocument_ignored` |
| S11 | Pre-1.4 index without `tagIds` mapping is upgraded on first use | ES IT | `EsTagIT.existingIndexWithoutTagIds_getsMappingUpgrade` |
| S12 | Re-index on assignment change (set/add/remove) | unit + IT | `TagServiceImplTest.addDocumentTag_publishesEventForDocument`, `TagFlowIT.searchFilter_combinesWithQuery` |
| S13 | Re-index all affected docs on rename (query by new name matches) | IT | `TagFlowIT.renameTag_reindexesAffectedDocs` |
| S14 | Re-index all affected docs on delete (filter by deleted id → empty) | IT | `TagFlowIT.deleteTag_detachesAndReindexes` |
| S15 | Synchroniser only pushes READY docs | unit | `SyncTest.onTagsChanged_skipsNonReadyDocuments` |
| S16 | ES down during sync → tag change still committed, request succeeds, error logged | unit | `SyncTest.onTagsChanged_searchFailure_isSwallowed` |
| S17 | Search `tagIds` > 20 → 400; duplicates deduped before reaching ES | contract | `TagContractTest.search_tooManyTagFilters_400`, `TagContractTest.search_passesDedupedTagFilter` |
| S18 | Tag names stay matchable by free-text query | ES IT | `EsTagIT.tagNames_areSearchableByQuery` |
| S19 | `updateTags` with an empty tag list clears tag fields; empty batch is a no-op | ES IT | `EsTagIT.updateTags_emptyListClearsTags`, `EsTagIT.updateTags_emptyBatch_isNoop` |
| S20 | Untagged doc at finalize → no ES round trip | unit | `SyncTest.onFinalized_untaggedDocument_noSearchCall` |
| S21 | Catalog read failure during sync is swallowed (logged) | unit | `SyncTest.onTagsChanged_catalogFailure_isSwallowed` |

## Web

| ID | Case | Level | Test |
|---|---|---|---|
| W01 | Tags store loads tags; `loading` toggles; empty list state | store | `tags.store.spec › load sets tags and toggles loading` |
| W02 | Tags store load failure → `error` set, tags unchanged | store | `tags.store.spec › load failure exposes error` |
| W03 | Create → appended + sorted; trims name; blank name rejected client-side | store | `tags.store.spec › create trims, sorts, rejects blank` |
| W04 | Create 409 → friendly "already exists" error, list unchanged | store | `tags.store.spec › create conflict maps to friendly error` |
| W05 | Rename / recolor → list updated | store | `tags.store.spec › update replaces tag in list` |
| W06 | Delete → removed from list; removed from active filter | store | `tags.store.spec › remove drops tag`, `documents.store.spec › tag deletion prunes filter and strips chips` |
| W07 | Documents store passes `tagIds` to list + search, combined with folder + query | store | `documents.store.spec › list and search include folder and tag filter` |
| W08 | Toggle tag filter on/off reloads; clear filter | store | `documents.store.spec › toggleTag adds/removes and reloads` |
| W09 | Add / remove tag on doc updates the row in place | store | `documents.store.spec › addTag / removeTag patch the document row` |
| W10 | Assignment failure surfaces an error and leaves the row unchanged | store | `documents.store.spec › tag assignment failure keeps row and sets error` |
| W11 | Filter sidebar: renders tags with counts, `aria-pressed` toggles, keyboard (Enter/Space via native button) | component | `tag-filter.spec › renders tags with counts and aria-pressed` |
| W12 | Filter sidebar: empty state | component | `tag-filter.spec › shows empty state` |
| W13 | Filter sidebar: clear button only when filter active | component | `tag-filter.spec › clear button appears only with active filter` |
| W14 | Manager: create with labelled inputs; submit disabled while blank or busy | component | `tag-manager.spec › create form is labelled and disabled when blank` |
| W15 | Manager: rename inline, Escape cancels | component | `tag-manager.spec › rename inline and escape cancels` |
| W16 | Manager: delete needs in-app confirm; cancel keeps tag | component | `tag-manager.spec › delete requires confirmation` |
| W17 | Manager: error rendered with `role="alert"` | component | `tag-manager.spec › shows store error as alert` |
| W18 | Chips: render name + colour; remove button has accessible label | component | `tag-chips.spec › renders chips with accessible remove buttons` |
| W19 | Chips: add select lists only unattached tags; disabled when none left | component | `tag-chips.spec › add select offers only unattached tags` |
| W20 | Chips: read-only mode hides controls | component | `tag-chips.spec › readonly hides controls` |
| W21 | Filter sidebar: loading state before first load; error + Retry | component | `tag-filter.spec › shows loading state before the first load completes`, `tag-filter.spec › shows an error with a retry when tags fail to load` |
| W22 | Manager: `role="dialog"`, `aria-modal`, labelled; Escape / Close emit close | component | `tag-manager.spec › is a labelled modal dialog that closes on Escape and via the close button` |
| W23 | Manager: Escape in rename field cancels rename only | component | `tag-manager.spec › Escape inside the rename field does not close the dialog` |
| W24 | Manager: recolor via labelled select; "None" clears | component | `tag-manager.spec › recolors via the labelled colour select; "None" clears` |
| W25 | Manager: failed create keeps the typed name | component | `tag-manager.spec › keeps the typed name when create fails` |
| W26 | Store: client-side 64-code-point limit; no colour omits the field | store | `tags.store.spec › create rejects names longer than 64 characters client-side`, `tags.store.spec › create omits colour when none is chosen` |
| W27 | Store: update of a vanished tag (404) drops it | store | `tags.store.spec › update of a vanished tag drops it and explains why` |
| W28 | Documents store: assignment reloads list when a filter is active; rename patches chips | store | `documents.store.spec › assignment reloads the list when a tag filter is active`, `documents.store.spec › tag rename / recolor patches chips in place` |

## Mobile (follow-up, not built here — Flutter not installed)

| ID | Case | Status |
|---|---|---|
| M01 | Dart client regenerates with new `Tags` endpoints + `tags` fields | Deferred — `build_runner` on a machine with Flutter |
| M02 | Tag chips + filter in the mobile catalog list | Deferred — Phase 2 mobile list work |
