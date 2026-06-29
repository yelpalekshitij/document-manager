package app.vaultdoc.gateway.documents.dto;

import java.util.List;

public record DocumentListResponse(
        List<DocumentSummary> items,
        int page,
        int pageSize,
        long totalCount) {}
