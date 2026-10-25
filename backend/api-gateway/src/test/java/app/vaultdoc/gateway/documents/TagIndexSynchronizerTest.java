package app.vaultdoc.gateway.documents;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.Tag;
import app.vaultdoc.catalog.event.DocumentTagsChangedEvent;
import app.vaultdoc.catalog.service.TagIndexResyncQueue;
import app.vaultdoc.catalog.service.TagService;
import app.vaultdoc.ingest.event.DocumentFinalizedEvent;
import app.vaultdoc.search.domain.DocumentTagsUpdate;
import app.vaultdoc.search.service.SearchService;

class TagIndexSynchronizerTest {

    private TagService tags;
    private SearchService search;
    private TagIndexResyncQueue queue;
    private TagIndexSynchronizer sync;

    private final UUID userId = UUID.randomUUID();
    private final UUID ready = UUID.randomUUID();
    private final UUID pending = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tags = mock(TagService.class);
        search = mock(SearchService.class);
        queue = mock(TagIndexResyncQueue.class);
        sync = new TagIndexSynchronizer(tags, search, queue);
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

    // ── chunking ────────────────────────────────────────────────────────────

    @Test
    void onTagsChanged_largeEventIsProcessedInChunks() {
        List<UUID> ids = IntStream.range(0, 2_500).mapToObj(i -> UUID.randomUUID()).toList();
        when(tags.tagsForIndexing(eq(userId), any())).thenAnswer(inv -> {
            Map<UUID, List<Tag>> current = new LinkedHashMap<>();
            for (Object id : (java.util.Collection<?>) inv.getArgument(1)) current.put((UUID) id, List.of());
            return current;
        });

        sync.onTagsChanged(new DocumentTagsChangedEvent(userId, Set.copyOf(ids)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> asked = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(tags, times(3)).tagsForIndexing(eq(userId), asked.capture());
        assertThat(asked.getAllValues()).allSatisfy(chunk -> assertThat(chunk).hasSizeLessThanOrEqualTo(1_000));
        assertThat(asked.getAllValues().stream().flatMap(java.util.Collection::stream)).containsExactlyInAnyOrderElementsOf(ids);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentTagsUpdate>> sent = ArgumentCaptor.forClass(List.class);
        verify(search, times(3)).updateTags(eq(userId), sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(1_000));
    }

    @Test
    void onTagsChanged_failingChunkIsQueuedAndOthersStillSync() {
        List<UUID> ids = IntStream.range(0, 1_500).mapToObj(i -> UUID.randomUUID()).toList();
        when(tags.tagsForIndexing(eq(userId), any())).thenAnswer(inv -> {
            Map<UUID, List<Tag>> current = new LinkedHashMap<>();
            for (Object id : (java.util.Collection<?>) inv.getArgument(1)) current.put((UUID) id, List.of());
            return current;
        });
        doThrow(new IllegalStateException("ES rejected bulk")).doNothing().when(search).updateTags(any(), anyList());

        sync.onTagsChanged(new DocumentTagsChangedEvent(userId, Set.copyOf(ids)));

        verify(search, times(2)).updateTags(eq(userId), anyList());
        verify(queue).enqueue(eq(userId), anyCollection(), contains("ES rejected bulk"));
    }

    // ── durable retry ───────────────────────────────────────────────────────

    @Test
    void onTagsChanged_searchFailure_isQueuedForRetry() {
        when(tags.tagsForIndexing(eq(userId), any())).thenReturn(Map.of(ready, List.of()));
        doThrow(new IllegalStateException("ES down")).when(search).updateTags(any(), anyList());

        sync.onTagsChanged(new DocumentTagsChangedEvent(userId, Set.of(ready)));

        verify(queue).enqueue(eq(userId), eq(Set.of(ready)), contains("ES down"));
    }

    @Test
    void onTagsChanged_catalogFailure_isQueuedForRetry() {
        when(tags.tagsForIndexing(eq(userId), any())).thenThrow(new IllegalStateException("db blip"));

        sync.onTagsChanged(new DocumentTagsChangedEvent(userId, Set.of(ready)));

        verify(queue).enqueue(eq(userId), eq(Set.of(ready)), contains("db blip"));
    }

    @Test
    void onFinalized_searchFailure_isQueuedForRetry() {
        Tag a = new Tag(UUID.randomUUID(), userId, "Alpha", null);
        when(tags.tagsForIndexing(userId, Set.of(ready))).thenReturn(Map.of(ready, List.of(a)));
        doThrow(new IllegalStateException("ES down")).when(search).updateTags(any(), anyList());

        sync.onDocumentFinalized(new DocumentFinalizedEvent(ready, userId, "application/pdf", "k", Instant.now()));

        verify(queue).enqueue(eq(userId), eq(Set.of(ready)), contains("ES down"));
    }

    @Test
    void enqueueFailure_isSwallowed() {
        when(tags.tagsForIndexing(eq(userId), any())).thenThrow(new IllegalStateException("db down"));
        doThrow(new IllegalStateException("db still down")).when(queue).enqueue(any(), anyCollection(), any());

        assertThatCode(() -> sync.onTagsChanged(new DocumentTagsChangedEvent(userId, Set.of(ready))))
                .doesNotThrowAnyException();
    }

    @Test
    void successfulSync_queuesNothing() {
        when(tags.tagsForIndexing(eq(userId), any())).thenReturn(Map.of(ready, List.of()));

        sync.onTagsChanged(new DocumentTagsChangedEvent(userId, Set.of(ready)));

        verifyNoInteractions(queue);
    }

    @Test
    void retryPending_resyncsDueDocumentsAndCompletesThem() {
        UUID otherUser = UUID.randomUUID();
        UUID otherDoc = UUID.randomUUID();
        Tag a = new Tag(UUID.randomUUID(), userId, "NewName", null);
        var mine = new TagIndexResyncQueue.Item(ready, userId, 1, 2);
        var theirs = new TagIndexResyncQueue.Item(otherDoc, otherUser, 1, 0);
        when(queue.due(anyInt())).thenReturn(List.of(mine, theirs));
        when(tags.tagsForIndexing(userId, Set.of(ready))).thenReturn(Map.of(ready, List.of(a)));
        when(tags.tagsForIndexing(otherUser, Set.of(otherDoc))).thenReturn(Map.of());

        assertThat(sync.retryPending()).isEqualTo(2);

        verify(search).updateTags(userId, List.of(new DocumentTagsUpdate(ready, List.of(a.getId()), List.of("NewName"))));
        verify(search, never()).updateTags(eq(otherUser), anyList());
        verify(queue).completed(List.of(mine));
        verify(queue).completed(List.of(theirs));
        verify(queue, never()).failed(anyCollection(), any());
    }

    @Test
    void retryPending_failureRecordsAttemptAndKeepsItem() {
        var item = new TagIndexResyncQueue.Item(ready, userId, 1, 0);
        when(queue.due(anyInt())).thenReturn(List.of(item));
        when(tags.tagsForIndexing(userId, Set.of(ready))).thenReturn(Map.of(ready, List.of()));
        doThrow(new IllegalStateException("ES still down")).when(search).updateTags(any(), anyList());

        assertThat(sync.retryPending()).isZero();

        verify(queue).failed(eq(List.of(item)), contains("ES still down"));
        verify(queue, never()).completed(anyCollection());
        verify(queue, never()).enqueue(any(), anyCollection(), any());
    }

    @Test
    void retryPending_queueUnavailable_isSwallowed() {
        when(queue.due(anyInt())).thenThrow(new IllegalStateException("db down"));

        assertThat(sync.retryPending()).isZero();
    }

    @SuppressWarnings("unchecked")
    private List<DocumentTagsUpdate> capturedUpdates() {
        ArgumentCaptor<List<DocumentTagsUpdate>> cap = ArgumentCaptor.forClass(List.class);
        verify(search).updateTags(eq(userId), cap.capture());
        return cap.getValue();
    }
}
