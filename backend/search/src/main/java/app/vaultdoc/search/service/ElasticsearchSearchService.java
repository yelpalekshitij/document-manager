package app.vaultdoc.search.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;

import app.vaultdoc.search.domain.DocumentIndexRecord;

public class ElasticsearchSearchService implements SearchService {

    private final ElasticsearchClient client;
    private final String indexName;
    private final Refresh refreshPolicy;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public ElasticsearchSearchService(ElasticsearchClient client, String indexName, String refreshPolicy) {
        this.client = client;
        this.indexName = indexName;
        this.refreshPolicy = parseRefresh(refreshPolicy);
    }

    private static Refresh parseRefresh(String value) {
        if (value == null) return Refresh.False;
        return switch (value.toLowerCase()) {
            case "true" -> Refresh.True;
            case "wait_for", "waitfor" -> Refresh.WaitFor;
            default -> Refresh.False;
        };
    }

    @Override
    public void index(DocumentIndexRecord record) {
        ensureIndex();
        Map<String, Object> body = toMap(record);
        try {
            client.index(i -> i
                    .index(indexName)
                    .id(record.documentId().toString())
                    .document(body)
                    .refresh(refreshPolicy));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(UUID userId, UUID documentId) {
        ensureIndex();
        try {
            client.delete(d -> d
                    .index(indexName)
                    .id(documentId.toString())
                    .refresh(refreshPolicy));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public SearchPage search(UUID userId, String query, UUID folderId, int page, int pageSize) {
        ensureIndex();
        int from = Math.max(0, page) * Math.max(1, pageSize);
        int size = Math.max(1, pageSize);

        BoolQuery.Builder bool = new BoolQuery.Builder();
        bool.filter(q -> q.term(t -> t.field("userId").value(userId.toString())));
        if (folderId != null) {
            bool.filter(q -> q.term(t -> t.field("folderId").value(folderId.toString())));
        }
        if (query != null && !query.isBlank()) {
            String trimmed = query.trim();
            bool.must(q -> q.bool(inner -> inner
                    .should(s -> s.match(m -> m.field("filename").query(trimmed)))
                    .should(s -> s.wildcard(w -> w.field("filename.raw").value("*" + trimmed.toLowerCase() + "*")))
                    .should(s -> s.term(t -> t.field("documentType").value(trimmed)))
                    .should(s -> s.term(t -> t.field("tags").value(trimmed)))
                    .should(s -> s.match(m -> m.field("ocrText").query(trimmed)))
                    .minimumShouldMatch("1")));
        } else {
            bool.must(q -> q.matchAll(m -> m));
        }

        try {
            SearchResponse<Map> resp = client.search(s -> s
                            .index(indexName)
                            .from(from)
                            .size(size)
                            .query(Query.of(q -> q.bool(bool.build())))
                            .sort(so -> so.field(f -> f.field("uploadedAt").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))),
                    Map.class);

            List<SearchHit> hits = new ArrayList<>(resp.hits().hits().size());
            for (var h : resp.hits().hits()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) h.source();
                if (src == null) continue;
                hits.add(toHit(src, h.score() == null ? 0d : h.score()));
            }
            long total = resp.hits().total() != null ? resp.hits().total().value() : hits.size();
            return new SearchPage(hits, total, page, pageSize);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void ensureIndex() {
        if (indexReady.get()) return;
        try {
            boolean exists = client.indices().exists(ExistsRequest.of(e -> e.index(indexName))).value();
            if (!exists) {
                client.indices().create(CreateIndexRequest.of(c -> c
                        .index(indexName)
                        .mappings(m -> m
                                .properties("userId", p -> p.keyword(k -> k))
                                .properties("documentId", p -> p.keyword(k -> k))
                                .properties("filename", p -> p.text(t -> t
                                        .analyzer("standard")
                                        .fields("raw", sub -> sub.keyword(k -> k.ignoreAbove(512)))))
                                .properties("mimeType", p -> p.keyword(k -> k))
                                .properties("sizeBytes", p -> p.long_(l -> l))
                                .properties("folderId", p -> p.keyword(k -> k))
                                .properties("folderPath", p -> p.keyword(k -> k))
                                .properties("documentType", p -> p.keyword(k -> k))
                                .properties("tags", p -> p.keyword(k -> k))
                                .properties("uploadedAt", p -> p.date(d -> d))
                                .properties("ocrText", p -> p.text(t -> t.analyzer("standard"))))));
            }
            indexReady.set(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> toMap(DocumentIndexRecord r) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("documentId", r.documentId().toString());
        map.put("userId", r.userId().toString());
        map.put("filename", r.filename());
        map.put("mimeType", r.mimeType());
        map.put("sizeBytes", r.sizeBytes());
        if (r.folderId() != null) map.put("folderId", r.folderId().toString());
        if (r.folderPath() != null) map.put("folderPath", r.folderPath());
        if (r.documentType() != null) map.put("documentType", r.documentType());
        if (r.tags() != null) map.put("tags", r.tags());
        map.put("uploadedAt", r.uploadedAt() == null ? null : r.uploadedAt().toString());
        if (r.ocrText() != null && !r.ocrText().isBlank()) {
            map.put("ocrText", r.ocrText());
        }
        return map;
    }

    private static SearchHit toHit(Map<String, Object> src, double score) {
        UUID documentId = UUID.fromString((String) src.get("documentId"));
        String filename = (String) src.get("filename");
        String mimeType = (String) src.get("mimeType");
        long sizeBytes = ((Number) src.getOrDefault("sizeBytes", 0)).longValue();
        UUID folderId = src.get("folderId") == null ? null : UUID.fromString((String) src.get("folderId"));
        String folderPath = (String) src.get("folderPath");
        String documentType = (String) src.get("documentType");
        Object uploaded = src.get("uploadedAt");
        java.time.Instant uploadedAt = uploaded == null ? null : java.time.Instant.parse(uploaded.toString());
        return new SearchHit(documentId, filename, mimeType, sizeBytes, folderId, folderPath, documentType, uploadedAt, score);
    }
}
