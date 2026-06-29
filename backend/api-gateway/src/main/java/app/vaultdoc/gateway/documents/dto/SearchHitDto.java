package app.vaultdoc.gateway.documents.dto;

import java.time.Instant;
import java.util.UUID;

import app.vaultdoc.search.service.SearchHit;

public record SearchHitDto(
        UUID documentId,
        String filename,
        String mimeType,
        long sizeBytes,
        UUID folderId,
        String folderPath,
        String documentType,
        Instant uploadedAt,
        double score) {

    public static SearchHitDto from(SearchHit h) {
        return new SearchHitDto(
                h.documentId(),
                h.filename(),
                h.mimeType(),
                h.sizeBytes(),
                h.folderId(),
                h.folderPath(),
                h.documentType(),
                h.uploadedAt(),
                h.score());
    }
}
