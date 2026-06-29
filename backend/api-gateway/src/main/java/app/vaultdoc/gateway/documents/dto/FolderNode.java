package app.vaultdoc.gateway.documents.dto;

import java.time.Instant;
import java.util.UUID;

import app.vaultdoc.catalog.domain.Folder;

public record FolderNode(
        UUID id,
        UUID parentId,
        String name,
        String color,
        String icon,
        int sortOrder,
        boolean system,
        Instant createdAt,
        Instant updatedAt) {

    public static FolderNode from(Folder f) {
        return new FolderNode(
                f.getId(),
                f.getParentId(),
                f.getName(),
                f.getColor(),
                f.getIcon(),
                f.getSortOrder(),
                f.isSystem(),
                f.getCreatedAt(),
                f.getUpdatedAt());
    }
}
