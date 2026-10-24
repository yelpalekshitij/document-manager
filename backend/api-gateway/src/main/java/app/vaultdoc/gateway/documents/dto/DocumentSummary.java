package app.vaultdoc.gateway.documents.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.vaultdoc.catalog.domain.Document;
import app.vaultdoc.catalog.domain.Tag;

public record DocumentSummary(
        UUID id,
        UUID folderId,
        String filename,
        String mimeType,
        long sizeBytes,
        String status,
        String documentType,
        Instant uploadedAt,
        String ocrStatus,
        List<TagRefDto> tags) {

    public static DocumentSummary from(Document doc, List<Tag> tags) {
        return new DocumentSummary(
                doc.getId(),
                doc.getFolderId(),
                doc.getOriginalFilename(),
                doc.getMimeType(),
                doc.getSizeBytes(),
                doc.getStatus().name(),
                doc.getDocumentType(),
                doc.getUploadedAt(),
                doc.getOcrStatus() == null ? null : doc.getOcrStatus().name(),
                TagRefDto.from(tags));
    }
}
