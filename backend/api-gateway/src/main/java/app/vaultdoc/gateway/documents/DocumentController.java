package app.vaultdoc.gateway.documents;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.vaultdoc.catalog.domain.Document;
import app.vaultdoc.catalog.domain.Tag;
import app.vaultdoc.catalog.service.DocumentService;
import app.vaultdoc.catalog.service.TagInput;
import app.vaultdoc.catalog.service.TagService;
import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;
import app.vaultdoc.core.storage.StorageProperties;
import app.vaultdoc.gateway.documents.dto.DocumentDetail;
import app.vaultdoc.gateway.documents.dto.DocumentDownloadResponse;
import app.vaultdoc.gateway.documents.dto.DocumentListResponse;
import app.vaultdoc.gateway.documents.dto.DocumentSummary;
import app.vaultdoc.gateway.documents.dto.FinalizeUploadRequest;
import app.vaultdoc.gateway.documents.dto.InitiateUploadRequest;
import app.vaultdoc.gateway.documents.dto.InitiateUploadResponse;
import app.vaultdoc.ingest.service.IngestService;
import app.vaultdoc.search.service.SearchService;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final IngestService ingest;
    private final DocumentService documents;
    private final ObjectStorage storage;
    private final SearchService search;
    private final TagService tags;
    private final Duration downloadTtl;

    public DocumentController(
            IngestService ingest,
            DocumentService documents,
            ObjectStorage storage,
            SearchService search,
            TagService tags,
            StorageProperties storageProperties) {
        this.ingest = ingest;
        this.documents = documents;
        this.storage = storage;
        this.search = search;
        this.tags = tags;
        this.downloadTtl = storageProperties.presignGetTtl();
    }

    @PostMapping("/initiate-upload")
    public ResponseEntity<InitiateUploadResponse> initiate(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody InitiateUploadRequest req) {
        var ticket = ingest.initiateUpload(
                UUID.fromString(userId),
                new IngestService.InitiateUploadCommand(
                        req.filename(),
                        req.mimeType(),
                        req.sizeBytes(),
                        req.sha256(),
                        req.folderId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new InitiateUploadResponse(
                ticket.documentId(),
                ticket.storageKey(),
                ticket.uploadUrl().toString(),
                ticket.expiresInSeconds()));
    }

    @PostMapping("/finalize")
    public ResponseEntity<DocumentDetail> finalize(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody FinalizeUploadRequest req) {
        UUID uid = UUID.fromString(userId);
        Document doc = ingest.finalizeUpload(uid, req.documentId());
        return ResponseEntity.ok(DocumentDetail.from(doc, tagsOf(uid, doc.getId())));
    }

    @GetMapping
    public ResponseEntity<DocumentListResponse> list(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) List<UUID> tagIds) {
        UUID uid = UUID.fromString(userId);
        Set<UUID> tagFilter = TagInput.normalizeFilter(tagIds);
        var p = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, pageSize), 200),
                Sort.by(Sort.Direction.DESC, "uploadedAt"));
        var result = documents.list(uid, folderId, tagFilter, p);
        // One batched tag lookup for the whole page, not one per row.
        Map<UUID, List<Tag>> tagsByDoc = tags.tagsForDocuments(uid,
                result.getContent().stream().map(Document::getId).toList());
        List<DocumentSummary> items = result.getContent().stream()
                .map(d -> DocumentSummary.from(d, tagsByDoc.getOrDefault(d.getId(), List.of())))
                .toList();
        return ResponseEntity.ok(new DocumentListResponse(items, page, pageSize, result.getTotalElements()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentDetail> get(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id) {
        UUID uid = UUID.fromString(userId);
        var doc = documents.findByIdForUser(uid, id);
        return ResponseEntity.ok(DocumentDetail.from(doc, tagsOf(uid, id)));
    }

    private List<Tag> tagsOf(UUID userId, UUID documentId) {
        return tags.tagsForDocuments(userId, List.of(documentId)).getOrDefault(documentId, List.of());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<DocumentDownloadResponse> download(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id) {
        var doc = documents.findByIdForUser(UUID.fromString(userId), id);
        var url = storage.presignGet(new StorageKey(doc.getStorageKey()), downloadTtl);
        return ResponseEntity.ok(new DocumentDownloadResponse(url.toString(), downloadTtl.toSeconds()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id) {
        UUID uid = UUID.fromString(userId);
        var doc = documents.findByIdForUser(uid, id);
        documents.softDelete(uid, id);
        try {
            storage.delete(new StorageKey(doc.getStorageKey()));
        } catch (RuntimeException ignored) {
            // best-effort blob cleanup; document is already DELETED in the catalog.
        }
        try {
            search.delete(uid, id);
        } catch (RuntimeException ignored) {
            // best-effort de-indexing; periodic reconcile can catch stragglers.
        }
        return ResponseEntity.noContent().build();
    }
}
