package app.vaultdoc.gateway.documents;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.vaultdoc.catalog.service.TagService;
import app.vaultdoc.gateway.documents.dto.CreateTagRequest;
import app.vaultdoc.gateway.documents.dto.TagDto;
import app.vaultdoc.gateway.documents.dto.TagListResponse;
import app.vaultdoc.gateway.documents.dto.UpdateTagRequest;

@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    private final TagService tags;

    public TagController(TagService tags) {
        this.tags = tags;
    }

    @GetMapping
    public ResponseEntity<TagListResponse> list(@AuthenticationPrincipal String userId) {
        List<TagDto> items = tags.listForUser(UUID.fromString(userId)).stream()
                .map(TagDto::from)
                .toList();
        return ResponseEntity.ok(new TagListResponse(items));
    }

    @PostMapping
    public ResponseEntity<TagDto> create(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody CreateTagRequest req) {
        var tag = tags.create(UUID.fromString(userId), req.name(), req.color());
        return ResponseEntity.status(HttpStatus.CREATED).body(TagDto.from(tag, 0));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<TagDto> update(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTagRequest req) {
        UUID uid = UUID.fromString(userId);
        var tag = tags.update(uid, id, req.name(), req.color());
        return ResponseEntity.ok(TagDto.from(tag, tags.documentCount(uid, id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id) {
        tags.delete(UUID.fromString(userId), id);
        return ResponseEntity.noContent().build();
    }
}
