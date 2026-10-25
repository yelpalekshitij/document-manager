package app.vaultdoc.gateway.documents;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.vaultdoc.gateway.support.DocumentITBase;
import app.vaultdoc.gateway.support.RecordingEmailSender;

/**
 * Tags end to end against real Postgres + Elasticsearch + MinIO + Redis:
 * CRUD, uniqueness, assignment, tenant isolation, list + search filters, and
 * index consistency after rename/delete/finalize/OCR.
 */
@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class TagFlowIT extends DocumentITBase {

    private static final String PROBLEMS = "https://vaultdoc.app/problems/";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};

    @DynamicPropertySource
    static void overrideRedisPassword(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordingEmailSender mail;
    @Autowired JdbcTemplate jdbc;

    // ── CRUD ────────────────────────────────────────────────────────────────

    @Test
    void list_emptyForNewUser_noSeededTags() throws Exception {
        String token = registerVerifyAndLogin();

        as(token, get("/api/v1/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(0));
    }

    @Test
    void crud_createListRenameRecolorDelete() throws Exception {
        String token = registerVerifyAndLogin();

        JsonNode created = body(as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  Tax   2024 \",\"color\":\"#AABBCC\"}"))
                .andExpect(status().isCreated()));
        String id = created.get("id").asString();
        assertThat(created.get("name").asString()).isEqualTo("Tax 2024");
        assertThat(created.get("color").asString()).isEqualTo("#aabbcc");
        assertThat(created.get("documentCount").asLong()).isZero();

        createTag(token, "bills");
        createTag(token, "Archive");

        // Sorted case-insensitively.
        as(token, get("/api/v1/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0].name").value("Archive"))
                .andExpect(jsonPath("$.tags[1].name").value("bills"))
                .andExpect(jsonPath("$.tags[2].name").value("Tax 2024"));

        as(token, patch("/api/v1/tags/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Taxes\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Taxes"))
                .andExpect(jsonPath("$.color").value("#aabbcc"));

        as(token, patch("/api/v1/tags/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"color\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Taxes"))
                .andExpect(jsonPath("$.color").doesNotExist());

        as(token, delete("/api/v1/tags/" + id)).andExpect(status().isNoContent());
        expectProblem(as(token, delete("/api/v1/tags/" + id)), 404, "tag-not-found");
        as(token, get("/api/v1/tags")).andExpect(jsonPath("$.tags.length()").value(2));
    }

    @Test
    void validation_badNameAndColour_400() throws Exception {
        String token = registerVerifyAndLogin();

        expectProblem(as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \\t \"}")), 400, "invalid-tag");
        expectProblem(as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "x".repeat(65) + "\"}")), 400, "invalid-tag");
        expectProblem(as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ok\",\"color\":\"red\"}")), 400, "invalid-tag");
        as(token, get("/api/v1/tags")).andExpect(jsonPath("$.tags.length()").value(0));
    }

    @Test
    void conflicts_createAndRenameCollisions() throws Exception {
        String token = registerVerifyAndLogin();
        String tax = createTag(token, "tax");
        createTag(token, "Bills");

        expectProblem(as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"TAX\"}")), 409, "tag-name-conflict");
        expectProblem(as(token, patch("/api/v1/tags/" + tax).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bills\"}")), 409, "tag-name-conflict");

        // Changing only the case of its own name is not a collision.
        as(token, patch("/api/v1/tags/" + tax).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Tax\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tax"));
    }

    @Test
    void conflicts_whitespaceAndNormalisationVariants_409() throws Exception {
        String token = registerVerifyAndLogin();
        createTag(token, "Tax 2024");
        String bills = createTag(token, "Bills");
        createTag(token, "\u00c4rzte");                       // precomposed Ä (NFC)

        // Normalised (NFC + whitespace collapse + trim) *before* the conflict lookup.
        expectProblem(as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content(nameJson("  tax\t 2024 "))), 409, "tag-name-conflict");
        expectProblem(as(token, patch("/api/v1/tags/" + bills).contentType(MediaType.APPLICATION_JSON)
                .content(nameJson("TAX  2024"))), 409, "tag-name-conflict");
        expectProblem(as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content(nameJson("A\u0308rzte"))), 409, "tag-name-conflict");   // decomposed A + ¨
        as(token, get("/api/v1/tags")).andExpect(jsonPath("$.tags.length()").value(3));
    }

    @Test
    void concurrentCreate_atUserCap_neverExceeds500() throws Exception {
        String token = registerVerifyAndLogin();
        String first = createTag(token, "seed-0");
        UUID userId = jdbc.queryForObject("select user_id from tags where id = ?", UUID.class, UUID.fromString(first));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i < 499; i++) rows.add(new Object[] {userId, "seed-" + i});
        jdbc.batchUpdate("insert into tags (user_id, name) values (?, ?)", rows);

        int attempts = 4;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String name = "racer-" + i;
                calls.add(() -> as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                        .content(nameJson(name))).andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(calls)) statuses.add(f.get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from tags where user_id = ?", Long.class, userId)).isEqualTo(500L);
        expectProblem(as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content(nameJson("one-too-many"))), 409, "tag-limit-reached");
    }

    @Test
    void unicodeName_roundTripsAndConflictsCaseInsensitively() throws Exception {
        String token = registerVerifyAndLogin();
        String emoji = "📄".repeat(64);   // 64 code points, 128 UTF-16 units

        as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + emoji + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(emoji));
        createTag(token, "कर दस्तावेज़");
        createTag(token, "Ärzte");

        expectProblem(as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ärzte\"}")), 409, "tag-name-conflict");
        as(token, get("/api/v1/tags")).andExpect(jsonPath("$.tags.length()").value(3));
    }

    @Test
    void concurrentCreate_sameName_exactlyOneWins() throws Exception {
        String token = registerVerifyAndLogin();
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String name = i % 2 == 0 ? "Race" : "race";
                calls.add(() -> as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}")).andReturn().getResponse().getStatus());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) statuses.add(f.get());

            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        as(token, get("/api/v1/tags")).andExpect(jsonPath("$.tags.length()").value(1));
    }

    // ── assignment ──────────────────────────────────────────────────────────

    @Test
    void assignment_setAddRemove_idempotent() throws Exception {
        String token = registerVerifyAndLogin();
        String doc = upload(token, "lease.png", null);
        String a = createTag(token, "alpha");
        String b = createTag(token, "Beta");

        as(token, put("/api/v1/documents/" + doc + "/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[\"" + b + "\",\"" + a + "\",\"" + a + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(doc))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0].name").value("alpha"))
                .andExpect(jsonPath("$.tags[1].name").value("Beta"));

        // Detail + list expose the tags.
        as(token, get("/api/v1/documents/" + doc)).andExpect(jsonPath("$.tags.length()").value(2));
        as(token, get("/api/v1/documents")).andExpect(jsonPath("$.items[0].tags.length()").value(2));

        // Add again → no duplicate.
        as(token, put("/api/v1/documents/" + doc + "/tags/" + a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(2));

        as(token, delete("/api/v1/documents/" + doc + "/tags/" + a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0].name").value("Beta"));
        // Remove again → still 200, nothing changes.
        as(token, delete("/api/v1/documents/" + doc + "/tags/" + a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1));

        as(token, put("/api/v1/documents/" + doc + "/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(0));
        as(token, get("/api/v1/tags"))
                .andExpect(jsonPath("$.tags[0].documentCount").value(0))
                .andExpect(jsonPath("$.tags[1].documentCount").value(0));
    }

    @Test
    void concurrentAdd_sameTag_noDuplicateNoError() throws Exception {
        String token = registerVerifyAndLogin();
        String doc = upload(token, "race.png", null);
        String tag = createTag(token, "race");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(() -> as(token, put("/api/v1/documents/" + doc + "/tags/" + tag))
                        .andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(calls)) assertThat(f.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        as(token, get("/api/v1/documents/" + doc)).andExpect(jsonPath("$.tags.length()").value(1));
        as(token, get("/api/v1/tags")).andExpect(jsonPath("$.tags[0].documentCount").value(1));
    }

    @Test
    void addTag_beyond50_400_butAlreadyAttachedAt50_200() throws Exception {
        String token = registerVerifyAndLogin();
        String doc = upload(token, "busy.png", null);
        List<String> fifty = new ArrayList<>();
        for (int i = 0; i < 50; i++) fifty.add(createTag(token, "t" + i));
        String extra = createTag(token, "t50");
        setTags(token, doc, fifty.toArray(String[]::new));

        expectProblem(as(token, put("/api/v1/documents/" + doc + "/tags/" + extra)), 400, "invalid-tag");
        as(token, put("/api/v1/documents/" + doc + "/tags/" + fifty.get(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(50));

        // The document's own tag set still round-trips through the bulk endpoint.
        setTags(token, doc, fifty.toArray(String[]::new));
        as(token, get("/api/v1/documents/" + doc)).andExpect(jsonPath("$.tags.length()").value(50));
    }

    @Test
    void concurrentAdd_atDocumentCap_neverExceeds50() throws Exception {
        String token = registerVerifyAndLogin();
        String doc = upload(token, "contended.png", null);
        List<String> attached = new ArrayList<>();
        for (int i = 0; i < 49; i++) attached.add(createTag(token, "base" + i));
        setTags(token, doc, attached.toArray(String[]::new));
        List<String> racers = new ArrayList<>();
        for (int i = 0; i < 4; i++) racers.add(createTag(token, "racer" + i));

        ExecutorService pool = Executors.newFixedThreadPool(racers.size());
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (String tag : racers) {
                calls.add(() -> as(token, put("/api/v1/documents/" + doc + "/tags/" + tag))
                        .andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(calls)) statuses.add(f.get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsOnly(200, 400);
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        as(token, get("/api/v1/documents/" + doc)).andExpect(jsonPath("$.tags.length()").value(50));
    }

    @Test
    void concurrentDeleteAndAdd_neverReturns500() throws Exception {
        String token = registerVerifyAndLogin();
        List<String> docs = new ArrayList<>();
        for (int i = 0; i < 4; i++) docs.add(upload(token, "victim-" + i + ".png", null));
        ExecutorService pool = Executors.newFixedThreadPool(docs.size() + 1);
        try {
            for (int round = 0; round < 5; round++) {
                String tag = createTag(token, "doomed-" + round);
                List<Callable<Integer>> calls = new ArrayList<>();
                for (String doc : docs) {
                    calls.add(() -> as(token, put("/api/v1/documents/" + doc + "/tags/" + tag))
                            .andReturn().getResponse().getStatus());
                }
                calls.add(() -> as(token, delete("/api/v1/tags/" + tag)).andReturn().getResponse().getStatus());
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : pool.invokeAll(calls)) statuses.add(f.get());

                assertThat(statuses.subList(0, docs.size())).as("round %d adds", round).isSubsetOf(200, 404);
                assertThat(statuses.getLast()).as("round %d delete", round).isEqualTo(204);
            }
        } finally {
            pool.shutdownNow();
        }
        for (String doc : docs) {
            as(token, get("/api/v1/documents/" + doc)).andExpect(jsonPath("$.tags.length()").value(0));
        }
    }

    @Test
    void crossTenant_sameNameAllowed_andForeignTagIsNotFound() throws Exception {
        String alice = registerVerifyAndLogin();
        String bob = registerVerifyAndLogin();
        String aliceTag = createTag(alice, "Medical");
        String aliceDoc = upload(alice, "alice.png", null);
        String bobDoc = upload(bob, "bob.png", null);

        // Same name in another vault is fine.
        String bobTag = createTag(bob, "medical");
        as(bob, get("/api/v1/tags"))
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0].id").value(bobTag));

        // Alice's tag is invisible to Bob — indistinguishable from a missing one.
        expectProblem(as(bob, patch("/api/v1/tags/" + aliceTag).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"stolen\"}")), 404, "tag-not-found");
        expectProblem(as(bob, delete("/api/v1/tags/" + aliceTag)), 404, "tag-not-found");
        expectProblem(as(bob, put("/api/v1/documents/" + bobDoc + "/tags/" + aliceTag)), 404, "tag-not-found");
        expectProblem(as(bob, put("/api/v1/documents/" + bobDoc + "/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[\"" + bobTag + "\",\"" + aliceTag + "\"]}")), 404, "tag-not-found");
        // The failed set wrote nothing.
        as(bob, get("/api/v1/documents/" + bobDoc)).andExpect(jsonPath("$.tags.length()").value(0));

        // …and Alice's document is invisible too.
        expectProblem(as(bob, put("/api/v1/documents/" + aliceDoc + "/tags/" + bobTag)), 404, "document-not-found");
        expectProblem(as(bob, delete("/api/v1/documents/" + aliceDoc + "/tags/" + bobTag)), 404, "document-not-found");
        expectProblem(as(bob, put("/api/v1/documents/" + aliceDoc + "/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[]}")), 404, "document-not-found");

        as(alice, get("/api/v1/tags")).andExpect(jsonPath("$.tags[0].name").value("Medical"));
    }

    // ── list filter ─────────────────────────────────────────────────────────

    @Test
    void listFilter_andSemantics_combinesWithFolder() throws Exception {
        String token = registerVerifyAndLogin();
        String folder = createFolder(token, "Tax 2024");
        String tax = createTag(token, "tax");
        String paid = createTag(token, "paid");
        String inFolderBoth = upload(token, "f-both.png", folder);
        String inFolderTax = upload(token, "f-tax.png", folder);
        String rootBoth = upload(token, "r-both.png", null);
        upload(token, "untagged.png", null);
        setTags(token, inFolderBoth, tax, paid);
        setTags(token, inFolderTax, tax);
        setTags(token, rootBoth, tax, paid);

        assertThat(listIds(token, null)).hasSize(4);
        assertThat(listIds(token, null, tax)).containsExactlyInAnyOrder(inFolderBoth, inFolderTax, rootBoth);
        assertThat(listIds(token, null, tax, paid)).containsExactlyInAnyOrder(inFolderBoth, rootBoth);
        // Duplicates don't inflate the AND count.
        assertThat(listIds(token, null, tax, paid, tax)).containsExactlyInAnyOrder(inFolderBoth, rootBoth);
        assertThat(listIds(token, folder, tax, paid)).containsExactly(inFolderBoth);
        as(token, get("/api/v1/documents").param("tagIds", tax, paid))
                .andExpect(jsonPath("$.totalCount").value(2));
    }

    @Test
    void listFilter_paginates() throws Exception {
        String token = registerVerifyAndLogin();
        String tag = createTag(token, "paged");
        String oldest = upload(token, "p1.png", null);
        String middle = upload(token, "p2.png", null);
        upload(token, "untagged.png", null);
        String newest = upload(token, "p3.png", null);
        setTags(token, oldest, tag);
        setTags(token, middle, tag);
        setTags(token, newest, tag);

        JsonNode page0 = body(as(token, get("/api/v1/documents").param("tagIds", tag)
                .param("page", "0").param("pageSize", "2")).andExpect(status().isOk()));
        JsonNode page1 = body(as(token, get("/api/v1/documents").param("tagIds", tag)
                .param("page", "1").param("pageSize", "2")).andExpect(status().isOk()));

        assertThat(ids(page0)).containsExactly(newest, middle);     // newest first
        assertThat(ids(page1)).containsExactly(oldest);
        assertThat(page0.get("totalCount").asLong()).isEqualTo(3);
        assertThat(page1.get("totalCount").asLong()).isEqualTo(3);
        assertThat(page1.get("page").asInt()).isEqualTo(1);
    }

    @Test
    void listFilter_unknownOrForeignTag_emptyResult() throws Exception {
        String alice = registerVerifyAndLogin();
        String bob = registerVerifyAndLogin();
        String mine = createTag(alice, "mine");
        String doc = upload(alice, "doc.png", null);
        setTags(alice, doc, mine);
        String bobs = createTag(bob, "bobs");

        assertThat(listIds(alice, null, UUID.randomUUID().toString())).isEmpty();
        assertThat(listIds(alice, null, bobs)).isEmpty();
        assertThat(listIds(alice, null, mine, bobs)).isEmpty();
        // Bob filtering by Alice's tag sees nothing of Alice's.
        assertThat(listIds(bob, null, mine)).isEmpty();
    }

    @Test
    void list_countsExcludeDeletedDocuments() throws Exception {
        String token = registerVerifyAndLogin();
        String tag = createTag(token, "keep");
        String kept = upload(token, "kept.png", null);
        String gone = upload(token, "gone.png", null);
        setTags(token, kept, tag);
        setTags(token, gone, tag);
        as(token, get("/api/v1/tags")).andExpect(jsonPath("$.tags[0].documentCount").value(2));

        as(token, delete("/api/v1/documents/" + gone)).andExpect(status().isNoContent());

        as(token, get("/api/v1/tags")).andExpect(jsonPath("$.tags[0].documentCount").value(1));
        assertThat(listIds(token, null, tag)).containsExactly(kept);
        // A deleted document can't be tagged any more.
        expectProblem(as(token, put("/api/v1/documents/" + gone + "/tags/" + tag)), 404, "document-not-found");
    }

    // ── search filter + index consistency ───────────────────────────────────

    @Test
    void searchFilter_combinesWithQuery() throws Exception {
        String token = registerVerifyAndLogin();
        String tax = createTag(token, "tax");
        String passportTax = upload(token, "passport-scan.png", null);
        String passportPlain = upload(token, "passport-copy.png", null);
        String receiptTax = upload(token, "receipt.png", null);
        setTags(token, passportTax, tax);
        setTags(token, receiptTax, tax);

        assertThat(searchIds(token, "passport")).containsExactlyInAnyOrder(passportTax, passportPlain);
        assertThat(searchIds(token, "passport", tax)).containsExactly(passportTax);
        assertThat(searchIds(token, null, tax)).containsExactlyInAnyOrder(passportTax, receiptTax);
        as(token, get("/api/v1/search").param("q", "receipt").param("tagIds", tax))
                .andExpect(jsonPath("$.items[0].tagIds[0]").value(tax));
        assertThat(searchIds(token, null, UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void renameTag_reindexesAffectedDocs() throws Exception {
        String token = registerVerifyAndLogin();
        String tag = createTag(token, "OldName");
        String doc = upload(token, "scan-0001.png", null);
        setTags(token, doc, tag);
        assertThat(searchIds(token, "OldName")).containsExactly(doc);

        as(token, patch("/api/v1/tags/" + tag).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"NewName\"}")).andExpect(status().isOk());

        assertThat(searchIds(token, "NewName")).containsExactly(doc);
        assertThat(searchIds(token, "OldName")).isEmpty();
    }

    @Test
    void deleteTag_detachesAndReindexes() throws Exception {
        String token = registerVerifyAndLogin();
        String doomed = createTag(token, "Doomed");
        String kept = createTag(token, "Kept");
        String doc = upload(token, "scan-0002.png", null);
        setTags(token, doc, doomed, kept);
        assertThat(searchIds(token, null, doomed)).containsExactly(doc);

        as(token, delete("/api/v1/tags/" + doomed)).andExpect(status().isNoContent());

        as(token, get("/api/v1/documents/" + doc))
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0].name").value("Kept"));
        assertThat(searchIds(token, null, doomed)).isEmpty();
        assertThat(searchIds(token, "Doomed")).isEmpty();
        assertThat(searchIds(token, null, kept)).containsExactly(doc);
    }

    @Test
    void deleteTag_onManyDocs_reindexesAll() throws Exception {
        String token = registerVerifyAndLogin();
        String doomed = createTag(token, "Shredded");
        List<String> docs = new ArrayList<>();
        for (int i = 0; i < 4; i++) docs.add(upload(token, "bulk-" + i + ".png", null));
        for (String doc : docs) setTags(token, doc, doomed);
        assertThat(searchIds(token, null, doomed)).containsExactlyInAnyOrderElementsOf(docs);
        assertThat(searchIds(token, "Shredded")).containsExactlyInAnyOrderElementsOf(docs);

        as(token, delete("/api/v1/tags/" + doomed)).andExpect(status().isNoContent());

        assertThat(searchIds(token, null, doomed)).isEmpty();
        assertThat(searchIds(token, "Shredded")).isEmpty();
        for (String doc : docs) {
            as(token, get("/api/v1/documents/" + doc)).andExpect(jsonPath("$.tags.length()").value(0));
        }
    }

    @Test
    void tagBeforeFinalize_tagsReachSearchAfterFinalize() throws Exception {
        String token = registerVerifyAndLogin();
        String tag = createTag(token, "early");
        Initiated pending = initiate(token, "early.png", "image/png", PNG, null);

        // Tagging a PENDING document is allowed; it isn't in the index yet.
        setTags(token, pending.documentId(), tag);
        assertThat(searchIds(token, null, tag)).isEmpty();

        putAndFinalize(token, pending, "image/png", PNG)
                .andExpect(jsonPath("$.tags[0].name").value("early"));

        assertThat(searchIds(token, null, tag)).containsExactly(pending.documentId());
    }

    @Test
    void ocrReindex_preservesTags() throws Exception {
        String token = registerVerifyAndLogin();
        String tag = createTag(token, "ocr");
        byte[] pdf = newPdfWithText("Quarterly zebracorn statement");
        Initiated init = initiate(token, "statement.pdf", "application/pdf", pdf, null);
        putAndFinalize(token, init, "application/pdf", pdf);
        setTags(token, init.documentId(), tag);

        // The OCR consumer re-indexes the document after extraction; the tag
        // filter must still match once the OCR text is searchable.
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(searchIds(token, "zebracorn", tag))
                        .containsExactly(init.documentId()));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private record Initiated(String documentId, String uploadUrl) {}

    private ResultActions as(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static ResultActions expectProblem(ResultActions actions, int status, String slug) throws Exception {
        return actions
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.type").value(PROBLEMS + slug));
    }

    private String createTag(String token, String name) throws Exception {
        return body(as(token, post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())).get("id").asString();
    }

    private String nameJson(String name) {
        return json.writeValueAsString(java.util.Map.of("name", name));
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(n -> ids.add(n.get("id").asString()));
        return ids;
    }

    private String createFolder(String token, String name) throws Exception {
        return body(as(token, post("/api/v1/folders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())).get("id").asString();
    }

    private void setTags(String token, String documentId, String... tagIds) throws Exception {
        String ids = String.join("\",\"", tagIds);
        as(token, put("/api/v1/documents/" + documentId + "/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[\"" + ids + "\"]}"))
                .andExpect(status().isOk());
    }

    private List<String> listIds(String token, String folderId, String... tagIds) throws Exception {
        var req = get("/api/v1/documents");
        if (folderId != null) req.param("folderId", folderId);
        if (tagIds.length > 0) req.param("tagIds", tagIds);
        JsonNode items = body(as(token, req).andExpect(status().isOk())).get("items");
        List<String> ids = new ArrayList<>();
        items.forEach(n -> ids.add(n.get("id").asString()));
        return ids;
    }

    private List<String> searchIds(String token, String q, String... tagIds) throws Exception {
        var req = get("/api/v1/search");
        if (q != null) req.param("q", q);
        if (tagIds.length > 0) req.param("tagIds", tagIds);
        JsonNode items = body(as(token, req).andExpect(status().isOk())).get("items");
        List<String> ids = new ArrayList<>();
        items.forEach(n -> ids.add(n.get("documentId").asString()));
        return ids;
    }

    private String upload(String token, String filename, String folderId) throws Exception {
        Initiated init = initiate(token, filename, "image/png", PNG, folderId);
        putAndFinalize(token, init, "image/png", PNG);
        return init.documentId();
    }

    private Initiated initiate(String token, String filename, String mime, byte[] bytes, String folderId)
            throws Exception {
        String folderJson = folderId == null ? "" : ",\"folderId\":\"" + folderId + "\"";
        JsonNode resp = body(as(token, post("/api/v1/documents/initiate-upload")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"filename":"%s","mimeType":"%s","sizeBytes":%d,"sha256":"%s"%s}
                        """.formatted(filename, mime, bytes.length, sha256Hex(bytes), folderJson)))
                .andExpect(status().isCreated()));
        return new Initiated(resp.get("documentId").asString(), resp.get("uploadUrl").asString());
    }

    private ResultActions putAndFinalize(String token, Initiated init, String mime, byte[] bytes) throws Exception {
        HttpResponse<String> putResp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(init.uploadUrl()))
                        .header("Content-Type", mime)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(putResp.statusCode()).as("MinIO PUT: " + putResp.body()).isEqualTo(200);
        return as(token, post("/api/v1/documents/finalize").contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + init.documentId() + "\"}"))
                .andExpect(status().isOk());
    }

    private String registerVerifyAndLogin() throws Exception {
        String email = "tags-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isCreated());
        String verifyToken = mail.extractTokenFromLast(email);
        MvcResult verify = mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + verifyToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(verify.getResponse().getContentAsString()).get("accessToken").asString();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] newPdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
