package app.vaultdoc.gateway.documents.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import app.vaultdoc.catalog.domain.Document;

public record DocumentDetail(
        UUID id,
        UUID folderId,
        String filename,
        String mimeType,
        long sizeBytes,
        String sha256,
        String status,
        String documentType,
        LocalDate issuedAt,
        LocalDate expiresAt,
        Instant uploadedAt,
        Instant finalizedAt,
        String ocrStatus,
        String ocrText,
        Integer ocrPageCount,
        Instant ocrCompletedAt,
        String ocrFailureReason) {

    public static DocumentDetail from(Document doc) {
        return new DocumentDetail(
                doc.getId(),
                doc.getFolderId(),
                doc.getOriginalFilename(),
                doc.getMimeType(),
                doc.getSizeBytes(),
                doc.getSha256(),
                doc.getStatus().name(),
                doc.getDocumentType(),
                doc.getIssuedAt(),
                doc.getExpiresAt(),
                doc.getUploadedAt(),
                doc.getFinalizedAt(),
                doc.getOcrStatus() == null ? null : doc.getOcrStatus().name(),
                doc.getOcrText(),
                doc.getOcrPageCount(),
                doc.getOcrCompletedAt(),
                doc.getOcrFailureReason());
    }
}
