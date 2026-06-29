package app.vaultdoc.search.service;

import java.util.List;

public record SearchPage(
        List<SearchHit> hits,
        long totalHits,
        int page,
        int pageSize) {}
