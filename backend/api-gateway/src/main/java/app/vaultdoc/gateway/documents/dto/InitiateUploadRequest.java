package app.vaultdoc.gateway.documents.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record InitiateUploadRequest(
        @NotBlank @Size(max = 500) String filename,
        @NotBlank @Size(max = 120) String mimeType,
        @Positive long sizeBytes,
        @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$", message = "sha256 must be a 64-char hex string") String sha256,
        UUID folderId) {}
