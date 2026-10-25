package app.vaultdoc.search.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import app.vaultdoc.search.domain.DocumentIndexRecord;
import app.vaultdoc.search.domain.DocumentTagsUpdate;

/**
 * Tag filter + tag-field ownership against a real Elasticsearch 9. Each test
 * gets its own index so tests stay independent under parallel execution.
 */
class ElasticsearchSearchServiceTagIT {

    private static final ElasticsearchContainer ES = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.2.8"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static ElasticsearchClient client;

    private String indexName;
    private ElasticsearchSearchService search;

    private final UUID userId = UUID.randomUUID();
    private final UUID tax = UUID.randomUUID();
    private final UUID bills = UUID.randomUUID();

    @BeforeAll
    static void startEs() {
        ES.start();
        client = ElasticsearchClient.of(b -> b
                .host("http://" + ES.getHttpHostAddress())
                .jsonMapper(new Jackson3JsonpMapper()));
    }

    @BeforeEach
    void freshIndex() {
        indexName = "tag-it-" + UUID.randomUUID();
        search = new ElasticsearchSearchService(client, indexName, "true");
    }

    @Test
    void emptyTagFilter_isNoFilter() {
        UUID a = indexDoc("a.pdf", null);
        UUID b = indexDoc("b.pdf", null);
        tag(a, tax);

        assertThat(ids(search.search(userId, null, null, Set.of(), 0, 10))).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void tagFilter_singleAndAnd() {
        UUID onlyTax = indexDoc("tax-only.pdf", null);
        UUID both = indexDoc("both.pdf", null);
        UUID none = indexDoc("none.pdf", null);
        tag(onlyTax, tax);
        tag(both, tax, bills);

        SearchPage single = search.search(userId, null, null, Set.of(tax), 0, 10);
        assertThat(ids(single)).containsExactlyInAnyOrder(onlyTax, both);
        assertThat(single.hits()).filteredOn(h -> h.documentId().equals(both))
                .singleElement()
                .satisfies(h -> assertThat(h.tagIds()).containsExactlyInAnyOrder(tax, bills));

        assertThat(ids(search.search(userId, null, null, Set.of(tax, bills), 0, 10))).containsExactly(both);
        assertThat(ids(search.search(userId, null, null, Set.of(), 0, 10))).contains(none);
    }

    @Test
    void tagFilter_combinesWithFolderAndQuery() {
        UUID folder = UUID.randomUUID();
        UUID inFolderMatching = indexDoc("passport-scan.pdf", folder);
        UUID inFolderOtherName = indexDoc("receipt.pdf", folder);
        UUID outsideFolder = indexDoc("passport-copy.pdf", null);
        tag(inFolderMatching, tax);
        tag(inFolderOtherName, tax);
        tag(outsideFolder, tax);

        assertThat(ids(search.search(userId, "passport", folder, Set.of(tax), 0, 10)))
                .containsExactly(inFolderMatching);
        assertThat(ids(search.search(userId, null, folder, Set.of(tax), 0, 10)))
                .containsExactlyInAnyOrder(inFolderMatching, inFolderOtherName);
    }

    @Test
    void unknownTagFilter_emptyResult() {
        UUID a = indexDoc("a.pdf", null);
        tag(a, tax);

        SearchPage page = search.search(userId, null, null, Set.of(UUID.randomUUID()), 0, 10);
        assertThat(page.hits()).isEmpty();
        assertThat(page.totalHits()).isZero();
    }

    @Test
    void tagFilter_isTenantScoped() {
        UUID mine = indexDoc("mine.pdf", null);
        UUID otherUser = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        search.index(record(theirs, otherUser, "theirs.pdf", null));
        search.updateTags(otherUser, List.of(new DocumentTagsUpdate(theirs, List.of(tax), List.of("tax"))));
        tag(mine, tax);

        assertThat(ids(search.search(userId, null, null, Set.of(tax), 0, 10))).containsExactly(mine);
    }

    @Test
    void tagNames_areSearchableByQuery() {
        UUID a = indexDoc("scan-001.pdf", null);
        search.updateTags(userId, List.of(new DocumentTagsUpdate(a, List.of(tax), List.of("taxes"))));

        assertThat(ids(search.search(userId, "taxes", null, Set.of(), 0, 10))).containsExactly(a);
    }

    @Test
    void tagNames_matchQueryCaseInsensitively() {
        UUID a = indexDoc("scan-002.pdf", null);
        search.updateTags(userId, List.of(new DocumentTagsUpdate(a, List.of(tax), List.of("Tax Returns"))));

        assertThat(ids(search.search(userId, "tax returns", null, Set.of(), 0, 10))).containsExactly(a);
        assertThat(ids(search.search(userId, "TAX RETURNS", null, Set.of(), 0, 10))).containsExactly(a);
    }

    @Test
    void reindex_preservesTags() {
        UUID a = indexDoc("a.pdf", null);
        tag(a, tax);

        // What the OCR consumer does after extraction: a full record with empty tags + OCR text.
        search.index(new DocumentIndexRecord(a, userId, "a.pdf", "application/pdf", 10, null, null,
                null, List.of(), Instant.parse("2026-01-01T00:00:00Z"), "invoice total"));

        assertThat(ids(search.search(userId, null, null, Set.of(tax), 0, 10))).containsExactly(a);
        assertThat(ids(search.search(userId, "invoice", null, Set.of(tax), 0, 10))).containsExactly(a);
    }

    @Test
    void concurrentReindexAndTagUpdate_bothApply() throws Exception {
        // Phase 1.4 integration race: the OCR consumer's re-index and the tag
        // synchronizer's partial update hit the same ES doc at the same time.
        // Both are read-modify-write updates, so ES must retry on a version
        // conflict instead of failing one of them (which DLQ'd the OCR entry).
        ElasticsearchSearchService racy = new ElasticsearchSearchService(client, indexName, "false");
        UUID a = indexDoc("a.pdf", null);
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> writers = new java.util.ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            for (int w = 0; w < 4; w++) {
                writers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        racy.index(new DocumentIndexRecord(a, userId, "a.pdf", "application/pdf", 10, null, null,
                                null, List.of(), Instant.parse("2026-01-01T00:00:00Z"), "invoice total"));
                    }
                    return null;
                }));
                writers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        racy.updateTags(userId, List.of(new DocumentTagsUpdate(a, List.of(tax), List.of("tax"))));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : writers) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
        }
        client.indices().refresh(r -> r.index(indexName));

        assertThat(errors).isEmpty();
        assertThat(ids(search.search(userId, "invoice", null, Set.of(tax), 0, 10))).containsExactly(a);
    }

    @Test
    void reindex_clearsNulledFields() {
        UUID folder = UUID.randomUUID();
        UUID a = indexDoc("a.pdf", folder);

        search.index(record(a, userId, "a.pdf", null));   // moved to root

        assertThat(ids(search.search(userId, null, folder, Set.of(), 0, 10))).isEmpty();
        assertThat(search.search(userId, null, null, Set.of(), 0, 10).hits())
                .singleElement()
                .satisfies(h -> assertThat(h.folderId()).isNull());
    }

    @Test
    void updateTags_emptyListClearsTags() {
        UUID a = indexDoc("a.pdf", null);
        tag(a, tax);

        search.updateTags(userId, List.of(new DocumentTagsUpdate(a, List.of(), List.of())));

        assertThat(ids(search.search(userId, null, null, Set.of(tax), 0, 10))).isEmpty();
    }

    @Test
    void updateTags_missingDocument_ignored() {
        UUID present = indexDoc("present.pdf", null);
        UUID missing = UUID.randomUUID();

        assertThatCode(() -> search.updateTags(userId, List.of(
                new DocumentTagsUpdate(missing, List.of(tax), List.of("tax")),
                new DocumentTagsUpdate(present, List.of(tax), List.of("tax")))))
                .doesNotThrowAnyException();

        // The missing doc must not be created as a tag-only stub; the present one is updated.
        assertThat(ids(search.search(userId, null, null, Set.of(tax), 0, 10))).containsExactly(present);
    }

    @Test
    void updateTags_emptyBatch_isNoop() {
        assertThatCode(() -> search.updateTags(userId, List.of())).doesNotThrowAnyException();
    }

    @Test
    void existingIndexWithoutTagIds_getsMappingUpgrade() throws Exception {
        // A pre-1.4 index: same mapping as Phase 1.3 minus tagIds.
        client.indices().create(c -> c.index(indexName).mappings(m -> m
                .properties("userId", p -> p.keyword(k -> k))
                .properties("documentId", p -> p.keyword(k -> k))
                .properties("filename", p -> p.text(t -> t.analyzer("standard")
                        .fields("raw", sub -> sub.keyword(k -> k.ignoreAbove(512)))))
                .properties("mimeType", p -> p.keyword(k -> k))
                .properties("sizeBytes", p -> p.long_(l -> l))
                .properties("folderId", p -> p.keyword(k -> k))
                .properties("folderPath", p -> p.keyword(k -> k))
                .properties("documentType", p -> p.keyword(k -> k))
                .properties("tags", p -> p.keyword(k -> k))
                .properties("uploadedAt", p -> p.date(d -> d))
                .properties("ocrText", p -> p.text(t -> t.analyzer("standard")))));
        var upgraded = new ElasticsearchSearchService(client, indexName, "true");

        UUID a = UUID.randomUUID();
        upgraded.index(record(a, userId, "legacy.pdf", null));
        upgraded.updateTags(userId, List.of(new DocumentTagsUpdate(a, List.of(tax), List.of("tax"))));

        var mapping = client.indices().getMapping(g -> g.index(indexName)).get(indexName).mappings();
        assertThat(mapping.properties().get("tagIds").isKeyword()).isTrue();
        assertThat(ids(upgraded.search(userId, null, null, Set.of(tax), 0, 10))).containsExactly(a);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UUID indexDoc(String filename, UUID folderId) {
        UUID id = UUID.randomUUID();
        search.index(record(id, userId, filename, folderId));
        return id;
    }

    private DocumentIndexRecord record(UUID id, UUID owner, String filename, UUID folderId) {
        return new DocumentIndexRecord(id, owner, filename, "application/pdf", 10, folderId, null,
                null, List.of(), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private void tag(UUID documentId, UUID... tagIds) {
        List<UUID> ids = List.of(tagIds);
        List<String> names = ids.stream().map(id -> id.equals(tax) ? "tax" : "bills").toList();
        search.updateTags(userId, List.of(new DocumentTagsUpdate(documentId, ids, names)));
    }

    private static List<UUID> ids(SearchPage page) {
        return page.hits().stream().map(SearchHit::documentId).toList();
    }
}
