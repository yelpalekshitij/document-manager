package app.vaultdoc.gateway.documents;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.vaultdoc.catalog.domain.Document;
import app.vaultdoc.catalog.domain.Tag;
import app.vaultdoc.catalog.domain.TagWithCount;
import app.vaultdoc.catalog.service.DocumentNotFoundException;
import app.vaultdoc.catalog.service.DocumentService;
import app.vaultdoc.catalog.service.InvalidTagException;
import app.vaultdoc.catalog.service.TagLimitExceededException;
import app.vaultdoc.catalog.service.TagNameConflictException;
import app.vaultdoc.catalog.service.TagNotFoundException;
import app.vaultdoc.catalog.service.TagService;
import app.vaultdoc.core.auth.token.AccessTokenClaims;
import app.vaultdoc.core.auth.token.JwtIssuer;
import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageProperties;
import app.vaultdoc.gateway.config.ProblemDetailsHandler;
import app.vaultdoc.gateway.config.SecurityConfig;
import app.vaultdoc.ingest.service.IngestService;
import app.vaultdoc.search.service.SearchHit;
import app.vaultdoc.search.service.SearchPage;
import app.vaultdoc.search.service.SearchService;

/**
 * HTTP contract for the tag slice: status codes, ProblemDetails types,
 * request validation and parameter binding. Services are mocked; the real
 * {@link SecurityConfig} + JWT filter run so 401 behaviour is the production one.
 */
@WebMvcTest
@ContextConfiguration(classes = {
        TagController.class,
        DocumentTagController.class,
        DocumentController.class,
        SearchController.class,
        ProblemDetailsHandler.class,
        SecurityConfig.class,
        TagApiContractTest.TestBeans.class})
@TestPropertySource(properties = "vaultdoc.web.cors.allowed-origins=http://localhost:4200")
// @MockitoBean mocks are shared by every method of the cached context; the
// suite-wide concurrent mode would interleave stubbing and resets.
@Execution(ExecutionMode.SAME_THREAD)
class TagApiContractTest {

    private static final String PROBLEMS = "https://vaultdoc.app/problems/";
    private static final String TOKEN = "good-token";

    @TestConfiguration
    @EnableWebSecurity
    static class TestBeans {
        @Bean
        StorageProperties storageProperties() {
            return new StorageProperties(null, null);
        }
    }

    @Autowired MockMvc mvc;

    @MockitoBean TagService tags;
    @MockitoBean DocumentService documents;
    @MockitoBean IngestService ingest;
    @MockitoBean ObjectStorage storage;
    @MockitoBean SearchService search;
    @MockitoBean JwtIssuer jwt;

    private final UUID userId = UUID.randomUUID();
    private final UUID docId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        when(jwt.verify(TOKEN)).thenReturn(Optional.of(
                new AccessTokenClaims(userId, "u@example.com", Instant.now(), Instant.now().plusSeconds(600))));
    }

    // ── /tags ───────────────────────────────────────────────────────────────

    @Test
    void unauthenticated_401() throws Exception {
        mvc.perform(get("/api/v1/tags"))
                .andExpect(status().isUnauthorized());
        verify(tags, never()).listForUser(any());
    }

    @Test
    void list_returnsTagsWithCounts() throws Exception {
        Tag t = tag("Tax", "#112233");
        when(tags.listForUser(userId)).thenReturn(List.of(new TagWithCount(t, 4)));

        auth(get("/api/v1/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0].id").value(t.getId().toString()))
                .andExpect(jsonPath("$.tags[0].name").value("Tax"))
                .andExpect(jsonPath("$.tags[0].color").value("#112233"))
                .andExpect(jsonPath("$.tags[0].documentCount").value(4));
    }

    @Test
    void list_empty_returnsEmptyArray() throws Exception {
        when(tags.listForUser(userId)).thenReturn(List.of());

        auth(get("/api/v1/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags").isArray())
                .andExpect(jsonPath("$.tags.length()").value(0));
    }

    @Test
    void create_201ReturnsTag_scopedToTokenUser() throws Exception {
        Tag t = tag("Tax", null);
        when(tags.create(userId, "Tax", null)).thenReturn(t);

        auth(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                // A userId smuggled into the body must be ignored.
                .content("{\"name\":\"Tax\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(t.getId().toString()))
                .andExpect(jsonPath("$.documentCount").value(0));
        verify(tags).create(userId, "Tax", null);
    }

    @Test
    void create_blankName_400InvalidTag() throws Exception {
        when(tags.create(eq(userId), eq("   "), any())).thenThrow(new InvalidTagException("Tag name must not be blank"));

        expectProblem(auth(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \"}")), 400, "invalid-tag")
                .andExpect(jsonPath("$.detail").value("Tag name must not be blank"));
    }

    @Test
    void create_missingName_400Validation() throws Exception {
        expectProblem(auth(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"color\":\"#112233\"}")), 400, "validation");
        verify(tags, never()).create(any(), any(), any());
    }

    @Test
    void create_badColour_400() throws Exception {
        when(tags.create(userId, "Tax", "red")).thenThrow(new InvalidTagException("Tag colour must be a #rrggbb hex value"));

        expectProblem(auth(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Tax\",\"color\":\"red\"}")), 400, "invalid-tag");
    }

    @Test
    void create_conflict_409() throws Exception {
        when(tags.create(userId, "tax", null)).thenThrow(new TagNameConflictException("tax"));

        expectProblem(auth(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"tax\"}")), 409, "tag-name-conflict");
    }

    @Test
    void create_limit_409() throws Exception {
        when(tags.create(userId, "x", null)).thenThrow(new TagLimitExceededException(500));

        expectProblem(auth(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}")), 409, "tag-limit-reached");
    }

    @Test
    void create_malformedJson_400Problem() throws Exception {
        expectProblem(auth(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":")), 400, "malformed-request");
    }

    @Test
    void update_200_withCount() throws Exception {
        Tag t = tag("Bills", "#000000");
        when(tags.update(userId, t.getId(), "Bills", "")).thenReturn(t);
        when(tags.documentCount(userId, t.getId())).thenReturn(2L);

        auth(patch("/api/v1/tags/" + t.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bills\",\"color\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bills"))
                .andExpect(jsonPath("$.documentCount").value(2));
    }

    @Test
    void update_unknown_404() throws Exception {
        UUID id = UUID.randomUUID();
        when(tags.update(userId, id, "x", null)).thenThrow(new TagNotFoundException(id));

        expectProblem(auth(patch("/api/v1/tags/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}")), 404, "tag-not-found");
    }

    @Test
    void update_nonUuidPath_400Problem() throws Exception {
        expectProblem(auth(patch("/api/v1/tags/not-a-uuid").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}")), 400, "validation");
    }

    @Test
    void delete_204() throws Exception {
        UUID id = UUID.randomUUID();

        auth(delete("/api/v1/tags/" + id)).andExpect(status().isNoContent());
        verify(tags).delete(userId, id);
    }

    @Test
    void delete_unknown_404ProblemDetails() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new TagNotFoundException(id)).when(tags).delete(userId, id);

        expectProblem(auth(delete("/api/v1/tags/" + id)), 404, "tag-not-found")
                .andExpect(jsonPath("$.instance").value("/api/v1/tags/" + id));
    }

    // ── /documents/{id}/tags ────────────────────────────────────────────────

    @Test
    void setTags_200_returnsResultingTags() throws Exception {
        Tag a = tag("a", null);
        when(tags.setDocumentTags(userId, docId, List.of(a.getId(), a.getId()))).thenReturn(List.of(a));

        auth(put("/api/v1/documents/" + docId + "/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[\"" + a.getId() + "\",\"" + a.getId() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(docId.toString()))
                .andExpect(jsonPath("$.tags[0].id").value(a.getId().toString()))
                .andExpect(jsonPath("$.tags[0].name").value("a"));
    }

    @Test
    void setTags_tooMany_400() throws Exception {
        String ids = IntStream.range(0, 51).mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));

        expectProblem(auth(put("/api/v1/documents/" + docId + "/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[" + ids + "]}")), 400, "validation");
        verify(tags, never()).setDocumentTags(any(), any(), any());
    }

    @Test
    void setTags_nullElement_400() throws Exception {
        expectProblem(auth(put("/api/v1/documents/" + docId + "/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[null]}")), 400, "validation");
    }

    @Test
    void setTags_missingTagIds_400() throws Exception {
        expectProblem(auth(put("/api/v1/documents/" + docId + "/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{}")), 400, "validation");
    }

    @Test
    void setTags_nonUuidElement_400() throws Exception {
        expectProblem(auth(put("/api/v1/documents/" + docId + "/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[\"nope\"]}")), 400, "malformed-request");
    }

    @Test
    void setTags_foreignDocument_404() throws Exception {
        when(tags.setDocumentTags(userId, docId, List.of())).thenThrow(new DocumentNotFoundException(docId));

        expectProblem(auth(put("/api/v1/documents/" + docId + "/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagIds\":[]}")), 404, "document-not-found");
    }

    @Test
    void addTag_200() throws Exception {
        Tag a = tag("a", "#ffffff");
        when(tags.addDocumentTag(userId, docId, a.getId())).thenReturn(List.of(a));

        auth(put("/api/v1/documents/" + docId + "/tags/" + a.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0].color").value("#ffffff"));
    }

    @Test
    void addTag_unknownTag_404() throws Exception {
        UUID tagId = UUID.randomUUID();
        when(tags.addDocumentTag(userId, docId, tagId)).thenThrow(new TagNotFoundException(tagId));

        expectProblem(auth(put("/api/v1/documents/" + docId + "/tags/" + tagId)), 404, "tag-not-found");
    }

    @Test
    void removeTag_200_returnsRemaining() throws Exception {
        UUID tagId = UUID.randomUUID();
        when(tags.removeDocumentTag(userId, docId, tagId)).thenReturn(List.of());

        auth(delete("/api/v1/documents/" + docId + "/tags/" + tagId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(0));
    }

    // ── list + search filters ───────────────────────────────────────────────

    @Test
    void list_passesDedupedTagFilterAndEmbedsTags() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Tag tagA = new Tag(a, userId, "a", null);
        Document doc = document();
        when(documents.list(eq(userId), eq(null), any(), any())).thenReturn(new PageImpl<>(List.of(doc)));
        when(tags.tagsForDocuments(eq(userId), any())).thenReturn(Map.of(doc.getId(), List.of(tagA)));

        auth(get("/api/v1/documents").param("tagIds", a.toString(), b.toString(), a.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tags[0].id").value(a.toString()))
                .andExpect(jsonPath("$.items[0].tags[0].name").value("a"));
        verify(documents).list(eq(userId), eq(null), eq(new LinkedHashSet<>(List.of(a, b))), any());
    }

    @Test
    void list_withoutFilter_passesEmptySet() throws Exception {
        when(documents.list(eq(userId), eq(null), any(), any())).thenReturn(new PageImpl<>(List.of()));

        auth(get("/api/v1/documents")).andExpect(status().isOk());
        verify(documents).list(eq(userId), eq(null), eq(Set.of()), any());
    }

    @Test
    void list_tooManyTagFilters_400() throws Exception {
        String[] ids = IntStream.range(0, 21).mapToObj(i -> UUID.randomUUID().toString()).toArray(String[]::new);

        expectProblem(auth(get("/api/v1/documents").param("tagIds", ids)), 400, "invalid-tag");
        verify(documents, never()).list(any(), any(), any(), any());
    }

    @Test
    void list_nonUuidTagFilter_400Problem() throws Exception {
        expectProblem(auth(get("/api/v1/documents").param("tagIds", "nope")), 400, "validation");
    }

    @Test
    void get_embedsTags() throws Exception {
        Document doc = document();
        Tag t = tag("Tax", null);
        when(documents.findByIdForUser(userId, doc.getId())).thenReturn(doc);
        when(tags.tagsForDocuments(eq(userId), any())).thenReturn(Map.of(doc.getId(), List.of(t)));

        auth(get("/api/v1/documents/" + doc.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0].name").value("Tax"));
    }

    @Test
    void search_passesDedupedTagFilter() throws Exception {
        UUID a = UUID.randomUUID();
        var hit = new SearchHit(docId, "f.pdf", "application/pdf", 1, null, null, null,
                Instant.parse("2026-01-01T00:00:00Z"), 1.0, List.of(a));
        when(search.search(eq(userId), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new SearchPage(List.of(hit), 1, 0, 25));

        auth(get("/api/v1/search").param("q", "f").param("tagIds", a.toString(), a.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tagIds[0]").value(a.toString()));
        verify(search).search(eq(userId), eq("f"), eq(null), eq(Set.of(a)), eq(0), eq(25));
    }

    @Test
    void search_tooManyTagFilters_400() throws Exception {
        String[] ids = IntStream.range(0, 21).mapToObj(i -> UUID.randomUUID().toString()).toArray(String[]::new);

        expectProblem(auth(get("/api/v1/search").param("tagIds", ids)), 400, "invalid-tag");
        verify(search, never()).search(any(), anyString(), any(), any(), anyInt(), anyInt());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ResultActions auth(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + TOKEN));
    }

    private static ResultActions expectProblem(ResultActions actions, int status, String slug) throws Exception {
        return actions
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEMS + slug))
                .andExpect(jsonPath("$.status").value(status));
    }

    private Tag tag(String name, String color) {
        return new Tag(UUID.randomUUID(), userId, name, color);
    }

    private Document document() {
        UUID id = UUID.randomUUID();
        return new Document(id, userId, null, "f.pdf", "application/pdf", 1, "k/" + id, "a".repeat(64));
    }
}
