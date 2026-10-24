package app.vaultdoc.gateway.documents.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SetDocumentTagsRequest(
        @NotNull @Size(max = 50) List<@NotNull UUID> tagIds) {}
