package app.vaultdoc.gateway.documents.dto;

import java.util.List;

public record SearchResponse(
        List<SearchHitDto> items,
        long totalCount,
        int page,
        int pageSize) {}
