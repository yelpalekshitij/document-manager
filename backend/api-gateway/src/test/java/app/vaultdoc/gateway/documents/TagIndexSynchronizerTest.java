package app.vaultdoc.gateway.documents;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.Tag;
import app.vaultdoc.catalog.event.DocumentTagsChangedEvent;
import app.vaultdoc.catalog.service.TagService;
import app.vaultdoc.ingest.event.DocumentFinalizedEvent;
import app.vaultdoc.search.domain.DocumentTagsUpdate;
import app.vaultdoc.search.service.SearchService;

class TagIndexSynchronizerTest {

    private TagService tags;
    private SearchService search;
    private TagIndexSynchronizer sync;

    private final UUID userId = UUID.randomUUID();
    private final UUID ready = UUID.randomUUID();
    private final UUID pending = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tags = mock(TagService.class);
        search = mock(SearchService.class);
        sync = new TagIndexSynchronizer(tags, search);
    }

    @Test
    void onTagsChanged_pushesNamesAndIdsForEachReadyDocument() {
        Tag a = new Tag(UUID.randomUUID(), userId, "Alpha", null);
        Tag b = new Tag(UUID.randomUUID(), userId, "beta", "#000000");
        UUID cleared = UUID.randomUUID();
        Map<UUID, List<Tag>> current = new LinkedHashMap<>();
        current.put(ready, List.of(a, b));
        current.put(cleared, List.of());
        when(tags.tagsForIndexing(eq(userId), any())).thenReturn(current);

        sync.onTagsChanged(new DocumentTagsChangedEvent(userId, Set.of(ready, cleared)));

        List<DocumentTagsUpdate> sent = capturedUpdates();
        assertThat(sent).containsExactlyInAnyOrder(
                new DocumentTagsUpdate(ready, List.of(a.getId(), b.getId()), List.of("Alpha", "beta")),
                new DocumentTagsUpdate(cleared, List.of(), List.of()));
    }

    @Test
    void onTagsChanged_skipsNonReadyDocuments() {
        // tagsForIndexing only returns READY docs; a PENDING one is absent.
        when(tags.tagsForIndexing(eq(userId), any())).thenReturn(Map.of(ready, List.of()));

        sync.onTagsChanged(new DocumentTagsChangedEvent(userId, Set.of(ready, pending)));

        assertThat(capturedUpdates()).extracting(DocumentTagsUpdate::documentId).containsExactly(ready);
    }

    @Test
    void onTagsChanged_nothingIndexable_noSearchCall() {
        when(tags.tagsForIndexing(eq(userId), any())).thenReturn(Map.of());

        sync.onTagsChanged(new DocumentTagsChangedEvent(userId, Set.of(pending)));

        verify(search, never()).updateTags(any(), anyList());
    }

    @Test
    void onTagsChanged_searchFailure_isSwallowed() {
        when(tags.tagsForIndexing(eq(userId), any())).thenReturn(Map.of(ready, List.of()));
        doThrow(new IllegalStateException("ES down")).when(search).updateTags(any(), anyList());

        // The tag change is already committed; ES being down must not fail the request.
        assertThatCode(() -> sync.onTagsChanged(new DocumentTagsChangedEvent(userId, Set.of(ready))))
                .doesNotThrowAnyException();
    }

    @Test
    void onTagsChanged_catalogFailure_isSwallowed() {
        when(tags.tagsForIndexing(eq(userId), any())).thenThrow(new IllegalStateException("db blip"));

        assertThatCode(() -> sync.onTagsChanged(new DocumentTagsChangedEvent(userId, Set.of(ready))))
                .doesNotThrowAnyException();
    }

    @Test
    void onFinalized_pushesCurrentTags() {
        Tag a = new Tag(UUID.randomUUID(), userId, "Alpha", null);
        when(tags.tagsForIndexing(userId, Set.of(ready))).thenReturn(Map.of(ready, List.of(a)));

        sync.onDocumentFinalized(new DocumentFinalizedEvent(ready, userId, "application/pdf", "k", Instant.now()));

        assertThat(capturedUpdates()).containsExactly(
                new DocumentTagsUpdate(ready, List.of(a.getId()), List.of("Alpha")));
    }

    @Test
    void onFinalized_untaggedDocument_noSearchCall() {
        // Common case: nothing was tagged before finalize — skip the ES round trip.
        when(tags.tagsForIndexing(userId, Set.of(ready))).thenReturn(Map.of(ready, List.of()));

        sync.onDocumentFinalized(new DocumentFinalizedEvent(ready, userId, "application/pdf", "k", Instant.now()));

        verify(search, never()).updateTags(any(), anyList());
    }

    @SuppressWarnings("unchecked")
    private List<DocumentTagsUpdate> capturedUpdates() {
        ArgumentCaptor<List<DocumentTagsUpdate>> cap = ArgumentCaptor.forClass(List.class);
        verify(search).updateTags(eq(userId), cap.capture());
        return cap.getValue();
    }
}
