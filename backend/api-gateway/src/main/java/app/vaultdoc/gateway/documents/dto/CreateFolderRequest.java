package app.vaultdoc.gateway.documents.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFolderRequest(
        @NotBlank @Size(max = 200) String name,
        UUID parentId,
        @Size(max = 16) String color,
        @Size(max = 64) String icon) {}
