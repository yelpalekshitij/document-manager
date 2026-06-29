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

import app.vaultdoc.catalog.service.FolderNotFoundException;
import app.vaultdoc.catalog.service.FolderService;
import app.vaultdoc.gateway.documents.dto.CreateFolderRequest;
import app.vaultdoc.gateway.documents.dto.FolderNode;
import app.vaultdoc.gateway.documents.dto.FolderTreeResponse;
import app.vaultdoc.gateway.documents.dto.UpdateFolderRequest;

@RestController
@RequestMapping("/api/v1/folders")
public class FolderController {

    private final FolderService folders;

    public FolderController(FolderService folders) {
        this.folders = folders;
    }

    @GetMapping
    public ResponseEntity<FolderTreeResponse> list(@AuthenticationPrincipal String userId) {
        List<FolderNode> tree = folders.listForUser(UUID.fromString(userId)).stream()
                .map(FolderNode::from)
                .toList();
        return ResponseEntity.ok(new FolderTreeResponse(tree));
    }

    @PostMapping
    public ResponseEntity<FolderNode> create(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody CreateFolderRequest req) {
        var folder = folders.create(
                UUID.fromString(userId),
                req.name(),
                req.parentId(),
                req.color(),
                req.icon());
        return ResponseEntity.status(HttpStatus.CREATED).body(FolderNode.from(folder));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<FolderNode> update(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateFolderRequest req) {
        var folder = folders.update(
                UUID.fromString(userId),
                id,
                req.name(),
                req.parentId(),
                req.color(),
                req.icon());
        return ResponseEntity.ok(FolderNode.from(folder));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID id) {
        boolean removed = folders.delete(UUID.fromString(userId), id);
        if (!removed) {
            throw new FolderNotFoundException(id);
        }
        return ResponseEntity.noContent().build();
    }
}
