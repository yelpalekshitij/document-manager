package app.vaultdoc.gateway.documents;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.vaultdoc.catalog.service.TagService;
import app.vaultdoc.gateway.documents.dto.DocumentTagsResponse;
import app.vaultdoc.gateway.documents.dto.SetDocumentTagsRequest;
import app.vaultdoc.gateway.documents.dto.TagRefDto;

/**
 * Document↔tag assignment. Every operation returns the document's resulting
 * tag list so clients can update a row without refetching it.
 */
@RestController
@RequestMapping("/api/v1/documents/{id}/tags")
public class DocumentTagController {

    private final TagService tags;

    public DocumentTagController(TagService tags) {
        this.tags = tags;
    }

    @PutMapping
    public ResponseEntity<DocumentTagsResponse> set(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @Valid @RequestBody SetDocumentTagsRequest req) {
        var result = tags.setDocumentTags(UUID.fromString(userId), id, req.tagIds());
        return ResponseEntity.ok(new DocumentTagsResponse(id, TagRefDto.from(result)));
    }

    @PutMapping("/{tagId}")
    public ResponseEntity<DocumentTagsResponse> add(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @PathVariable UUID tagId) {
        var result = tags.addDocumentTag(UUID.fromString(userId), id, tagId);
        return ResponseEntity.ok(new DocumentTagsResponse(id, TagRefDto.from(result)));
    }

    @DeleteMapping("/{tagId}")
    public ResponseEntity<DocumentTagsResponse> remove(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @PathVariable UUID tagId) {
        var result = tags.removeDocumentTag(UUID.fromString(userId), id, tagId);
        return ResponseEntity.ok(new DocumentTagsResponse(id, TagRefDto.from(result)));
    }
}
