package app.vaultdoc.gateway.documents.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record FinalizeUploadRequest(@NotNull UUID documentId) {}
