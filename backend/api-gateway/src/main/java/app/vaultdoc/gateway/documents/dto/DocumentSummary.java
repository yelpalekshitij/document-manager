package app.vaultdoc.gateway.documents.dto;

import java.time.Instant;
import java.util.UUID;

import app.vaultdoc.catalog.domain.Document;

public record DocumentSummary(
        UUID id,
        UUID folderId,
        String filename,
        String mimeType,
        long sizeBytes,
        String status,
        String documentType,
        Instant uploadedAt,
        String ocrStatus) {

    public static DocumentSummary from(Document doc) {
        return new DocumentSummary(
                doc.getId(),
                doc.getFolderId(),
                doc.getOriginalFilename(),
                doc.getMimeType(),
                doc.getSizeBytes(),
                doc.getStatus().name(),
                doc.getDocumentType(),
                doc.getUploadedAt(),
                doc.getOcrStatus() == null ? null : doc.getOcrStatus().name());
    }
}
