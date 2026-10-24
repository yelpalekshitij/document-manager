package app.vaultdoc.catalog.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.Document;
import app.vaultdoc.catalog.domain.DocumentRepository;
import app.vaultdoc.catalog.domain.DocumentTagRepository;
import app.vaultdoc.catalog.domain.Tag;
import app.vaultdoc.catalog.domain.TagRepository;
import app.vaultdoc.catalog.domain.TagWithCount;
import app.vaultdoc.catalog.event.DocumentTagsChangedEvent;

class TagServiceImplTest {

    private TagRepository tags;
    private DocumentTagRepository documentTags;
    private DocumentRepository documents;
    private ApplicationEventPublisher events;
    private TagServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID docId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tags = mock(TagRepository.class);
        documentTags = mock(DocumentTagRepository.class);
        documents = mock(DocumentRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new TagServiceImpl(tags, documentTags, documents, events);
        when(tags.saveAndFlush(any(Tag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tags.findByUserIdAndNameIgnoreCase(any(), any())).thenReturn(Optional.empty());
    }

    // ── create ──────────────────────────────────────────────────────────────

    @Test
    void create_persistsNormalisedNameAndColour() {
        Tag created = service.create(userId, "  Tax   2024 ", "#AABBCC");

        ArgumentCaptor<Tag> cap = ArgumentCaptor.forClass(Tag.class);
        verify(tags).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getUserId()).isEqualTo(userId);
        assertThat(created.getName()).isEqualTo("Tax 2024");
        assertThat(created.getColor()).isEqualTo("#aabbcc");
        verifyNoEvent();
    }

    @Test
    void create_withoutColour_storesNull() {
        assertThat(service.create(userId, "tax", null).getColor()).isNull();
    }

    @Test
    void create_invalidInput_rejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.create(userId, "   ", null)).isInstanceOf(InvalidTagException.class);
        assertThatThrownBy(() -> service.create(userId, "ok", "blue")).isInstanceOf(InvalidTagException.class);
        verify(tags, never()).saveAndFlush(any());
    }

    @Test
    void create_caseInsensitiveDuplicate_throwsConflict() {
        when(tags.findByUserIdAndNameIgnoreCase(userId, "TAX"))
                .thenReturn(Optional.of(tag("tax")));

        assertThatThrownBy(() -> service.create(userId, "TAX", null))
                .isInstanceOf(TagNameConflictException.class)
                .hasMessageContaining("TAX");
        verify(tags, never()).saveAndFlush(any());
    }

    @Test
    void create_uniqueIndexRace_translatedToConflict() {
        // Pre-check passed, but a concurrent request won the unique index.
        when(tags.saveAndFlush(any(Tag.class))).thenThrow(new DataIntegrityViolationException("tags_unique_name"));

        assertThatThrownBy(() -> service.create(userId, "tax", null))
                .isInstanceOf(TagNameConflictException.class);
    }

    @Test
    void create_overLimit_throws() {
        when(tags.countByUserId(userId)).thenReturn((long) TagService.MAX_TAGS_PER_USER);

        assertThatThrownBy(() -> service.create(userId, "one-too-many", null))
                .isInstanceOf(TagLimitExceededException.class)
                .hasMessageContaining("500");
    }

    // ── update ──────────────────────────────────────────────────────────────

    @Test
    void update_rename_publishesReindexForTaggedDocs() {
        Tag t = tag("tax");
        UUID d2 = UUID.randomUUID();
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(documentTags.findDocumentIdsByTagId(t.getId())).thenReturn(List.of(docId, d2));

        Tag updated = service.update(userId, t.getId(), "taxes", null);

        assertThat(updated.getName()).isEqualTo("taxes");
        assertThat(capturedEvent().documentIds()).containsExactlyInAnyOrder(docId, d2);
    }

    @Test
    void update_renameOfUntaggedTag_noEvent() {
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(documentTags.findDocumentIdsByTagId(t.getId())).thenReturn(List.of());

        service.update(userId, t.getId(), "taxes", null);

        verifyNoEvent();
    }

    @Test
    void update_renameCollision_throwsConflict() {
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(tags.findByUserIdAndNameIgnoreCase(userId, "Bills")).thenReturn(Optional.of(tag("bills")));

        assertThatThrownBy(() -> service.update(userId, t.getId(), "Bills", null))
                .isInstanceOf(TagNameConflictException.class);
        assertThat(t.getName()).isEqualTo("tax");
    }

    @Test
    void update_renameRace_translatedToConflict() {
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(tags.saveAndFlush(any(Tag.class))).thenThrow(new DataIntegrityViolationException("tags_unique_name"));

        assertThatThrownBy(() -> service.update(userId, t.getId(), "bills", null))
                .isInstanceOf(TagNameConflictException.class);
        verifyNoEvent();
    }

    @Test
    void update_caseOnlyRenameOfSelf_allowed() {
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(tags.findByUserIdAndNameIgnoreCase(userId, "Tax")).thenReturn(Optional.of(t));
        when(documentTags.findDocumentIdsByTagId(t.getId())).thenReturn(List.of(docId));

        assertThat(service.update(userId, t.getId(), "Tax", null).getName()).isEqualTo("Tax");
        // Display name changed → the index's tag names change too.
        assertThat(capturedEvent().documentIds()).containsExactly(docId);
    }

    @Test
    void update_sameNameAgain_noEvent() {
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(tags.findByUserIdAndNameIgnoreCase(userId, "tax")).thenReturn(Optional.of(t));

        service.update(userId, t.getId(), " tax ", null);

        verifyNoEvent();
    }

    @Test
    void update_blankName_rejected() {
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.update(userId, t.getId(), "  ", null))
                .isInstanceOf(InvalidTagException.class);
    }

    @Test
    void update_recolorOnly_noReindexEvent() {
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));

        assertThat(service.update(userId, t.getId(), null, "#00FF00").getColor()).isEqualTo("#00ff00");
        verifyNoEvent();
        verify(documentTags, never()).findDocumentIdsByTagId(any());
    }

    @Test
    void update_emptyColourClears_absentKeeps() {
        Tag t = new Tag(UUID.randomUUID(), userId, "tax", "#112233");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));

        assertThat(service.update(userId, t.getId(), null, null).getColor()).isEqualTo("#112233");
        assertThat(service.update(userId, t.getId(), null, "").getColor()).isNull();
    }

    @Test
    void update_invalidColour_rejected() {
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.update(userId, t.getId(), null, "red"))
                .isInstanceOf(InvalidTagException.class);
    }

    @Test
    void update_emptyPatch_isNoop() {
        Tag t = new Tag(UUID.randomUUID(), userId, "tax", "#112233");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));

        Tag same = service.update(userId, t.getId(), null, null);

        assertThat(same.getName()).isEqualTo("tax");
        assertThat(same.getColor()).isEqualTo("#112233");
        verifyNoEvent();
    }

    @Test
    void update_unknownTag_notFound() {
        UUID id = UUID.randomUUID();
        when(tags.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(userId, id, "x", null))
                .isInstanceOf(TagNotFoundException.class);
    }

    // ── delete ──────────────────────────────────────────────────────────────

    @Test
    void delete_unknownTag_notFound() {
        UUID id = UUID.randomUUID();
        when(tags.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(userId, id)).isInstanceOf(TagNotFoundException.class);
        verify(tags, never()).delete(any());
    }

    @Test
    void delete_publishesReindexForPreviouslyTaggedDocs() {
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(documentTags.findDocumentIdsByTagId(t.getId())).thenReturn(List.of(docId));

        service.delete(userId, t.getId());

        verify(tags).delete(t);
        assertThat(capturedEvent().documentIds()).containsExactly(docId);
        assertThat(capturedEvent().userId()).isEqualTo(userId);
    }

    @Test
    void delete_untaggedTag_noEvent() {
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(documentTags.findDocumentIdsByTagId(t.getId())).thenReturn(List.of());

        service.delete(userId, t.getId());

        verify(tags).delete(t);
        verifyNoEvent();
    }

    // ── set ─────────────────────────────────────────────────────────────────

    @Test
    void setDocumentTags_replacesAndReturnsSorted() {
        givenDocument(docId);
        Tag zeta = tag("zeta");
        Tag alpha = tag("Alpha");
        Tag old = tag("old");
        when(tags.findAllByUserIdAndIdIn(eq(userId), anyCollection())).thenReturn(List.of(zeta, alpha));
        when(documentTags.findTagIdsByDocumentId(docId)).thenReturn(List.of(old.getId(), zeta.getId()));

        List<Tag> result = service.setDocumentTags(userId, docId, List.of(zeta.getId(), alpha.getId()));

        assertThat(result).extracting(Tag::getName).containsExactly("Alpha", "zeta");
        verify(documentTags).detach(docId, old.getId());
        verify(documentTags).attach(docId, alpha.getId());
        verify(documentTags, never()).attach(docId, zeta.getId());
        assertThat(capturedEvent().documentIds()).containsExactly(docId);
    }

    @Test
    void setDocumentTags_emptyClears() {
        givenDocument(docId);
        UUID existing = UUID.randomUUID();
        when(documentTags.findTagIdsByDocumentId(docId)).thenReturn(List.of(existing));

        assertThat(service.setDocumentTags(userId, docId, List.of())).isEmpty();

        verify(documentTags).detach(docId, existing);
        assertThat(capturedEvent().documentIds()).containsExactly(docId);
    }

    @Test
    void setDocumentTags_dedupes() {
        givenDocument(docId);
        Tag a = tag("a");
        when(tags.findAllByUserIdAndIdIn(eq(userId), anyCollection())).thenReturn(List.of(a));
        when(documentTags.findTagIdsByDocumentId(docId)).thenReturn(List.of());

        List<Tag> result = service.setDocumentTags(userId, docId, List.of(a.getId(), a.getId()));

        assertThat(result).containsExactly(a);
        verify(documentTags).attach(docId, a.getId());
    }

    @Test
    void setDocumentTags_tooMany_rejected() {
        givenDocument(docId);
        List<UUID> ids = IntStream.range(0, TagService.MAX_TAGS_PER_DOCUMENT + 1)
                .mapToObj(i -> UUID.randomUUID()).toList();

        assertThatThrownBy(() -> service.setDocumentTags(userId, docId, ids))
                .isInstanceOf(InvalidTagException.class)
                .hasMessageContaining("50");
    }

    @Test
    void setDocumentTags_nullElement_rejected() {
        givenDocument(docId);
        List<UUID> ids = new ArrayList<>();
        ids.add(null);

        assertThatThrownBy(() -> service.setDocumentTags(userId, docId, ids))
                .isInstanceOf(InvalidTagException.class);
    }

    @Test
    void setDocumentTags_foreignTag_notFound_noWrites() {
        givenDocument(docId);
        Tag mine = tag("mine");
        UUID foreign = UUID.randomUUID();
        // The repository is user-scoped, so the foreign id simply isn't returned.
        when(tags.findAllByUserIdAndIdIn(eq(userId), anyCollection())).thenReturn(List.of(mine));

        assertThatThrownBy(() -> service.setDocumentTags(userId, docId, List.of(mine.getId(), foreign)))
                .isInstanceOf(TagNotFoundException.class)
                .hasMessageContaining(foreign.toString());
        verify(documentTags, never()).attach(any(), any());
        verify(documentTags, never()).detach(any(), any());
        verifyNoEvent();
    }

    @Test
    void setDocumentTags_unchanged_noEvent() {
        givenDocument(docId);
        Tag a = tag("a");
        when(tags.findAllByUserIdAndIdIn(eq(userId), anyCollection())).thenReturn(List.of(a));
        when(documentTags.findTagIdsByDocumentId(docId)).thenReturn(List.of(a.getId()));

        assertThat(service.setDocumentTags(userId, docId, List.of(a.getId()))).containsExactly(a);
        verifyNoEvent();
    }

    @Test
    void setDocumentTags_foreignDocument_notFound() {
        when(documents.findByIdAndUserId(docId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setDocumentTags(userId, docId, List.of()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    // ── add / remove ────────────────────────────────────────────────────────

    @Test
    void addDocumentTag_publishesEventForDocument() {
        givenDocument(docId);
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(documentTags.attach(docId, t.getId())).thenReturn(1);
        givenDocumentTagRows(docId, t);

        assertThat(service.addDocumentTag(userId, docId, t.getId())).containsExactly(t);
        assertThat(capturedEvent().documentIds()).containsExactly(docId);
    }

    @Test
    void addDocumentTag_alreadyAttached_isNoopWithoutEvent() {
        givenDocument(docId);
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(documentTags.attach(docId, t.getId())).thenReturn(0);   // ON CONFLICT DO NOTHING
        givenDocumentTagRows(docId, t);

        assertThat(service.addDocumentTag(userId, docId, t.getId())).containsExactly(t);
        verifyNoEvent();
    }

    @Test
    void addDocumentTag_foreignDocument_notFound() {
        when(documents.findByIdAndUserId(docId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addDocumentTag(userId, docId, UUID.randomUUID()))
                .isInstanceOf(DocumentNotFoundException.class);
        verify(documentTags, never()).attach(any(), any());
    }

    @Test
    void addDocumentTag_deletedDocument_notFound() {
        Document deleted = document(docId);
        deleted.markDeleted();
        when(documents.findByIdAndUserId(docId, userId)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.addDocumentTag(userId, docId, UUID.randomUUID()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void addDocumentTag_foreignTag_notFound() {
        givenDocument(docId);
        UUID foreign = UUID.randomUUID();
        when(tags.findByIdAndUserId(foreign, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addDocumentTag(userId, docId, foreign))
                .isInstanceOf(TagNotFoundException.class);
        verify(documentTags, never()).attach(any(), any());
    }

    @Test
    void removeDocumentTag_detaches_publishesEvent() {
        givenDocument(docId);
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(documentTags.detach(docId, t.getId())).thenReturn(1);
        when(documentTags.findTagsForDocuments(eq(userId), anyCollection())).thenReturn(List.of());

        assertThat(service.removeDocumentTag(userId, docId, t.getId())).isEmpty();
        assertThat(capturedEvent().documentIds()).containsExactly(docId);
    }

    @Test
    void removeDocumentTag_notAttached_isNoopWithoutEvent() {
        givenDocument(docId);
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(documentTags.detach(docId, t.getId())).thenReturn(0);
        when(documentTags.findTagsForDocuments(eq(userId), anyCollection())).thenReturn(List.of());

        assertThat(service.removeDocumentTag(userId, docId, t.getId())).isEmpty();
        verifyNoEvent();
    }

    @Test
    void removeDocumentTag_foreignTag_notFound() {
        givenDocument(docId);
        UUID foreign = UUID.randomUUID();
        when(tags.findByIdAndUserId(foreign, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeDocumentTag(userId, docId, foreign))
                .isInstanceOf(TagNotFoundException.class);
        verify(documentTags, never()).detach(any(), any());
    }

    // ── reads ───────────────────────────────────────────────────────────────

    @Test
    void listForUser_delegatesToCountQuery() {
        var row = new TagWithCount(tag("tax"), 3);
        when(tags.findAllWithCounts(userId)).thenReturn(List.of(row));

        assertThat(service.listForUser(userId)).containsExactly(row);
    }

    @Test
    void documentCount_countsLiveDocumentsOfOwnedTag() {
        Tag t = tag("tax");
        when(tags.findByIdAndUserId(t.getId(), userId)).thenReturn(Optional.of(t));
        when(tags.countLiveDocuments(t.getId())).thenReturn(7L);

        assertThat(service.documentCount(userId, t.getId())).isEqualTo(7L);
    }

    @Test
    void documentCount_foreignTag_notFound() {
        UUID foreign = UUID.randomUUID();
        when(tags.findByIdAndUserId(foreign, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.documentCount(userId, foreign))
                .isInstanceOf(TagNotFoundException.class);
    }

    @Test
    void tagsForDocuments_groupsRowsAndFillsEmpty() {
        UUID other = UUID.randomUUID();
        Tag a = tag("a");
        Tag b = tag("b");
        when(documentTags.findTagsForDocuments(eq(userId), anyCollection())).thenReturn(List.of(
                new Object[] {docId, a},
                new Object[] {docId, b}));

        Map<UUID, List<Tag>> result = service.tagsForDocuments(userId, List.of(docId, other));

        assertThat(result.get(docId)).containsExactly(a, b);
        assertThat(result.get(other)).isEmpty();
    }

    @Test
    void tagsForDocuments_emptyInput_noQuery() {
        assertThat(service.tagsForDocuments(userId, List.of())).isEmpty();
        verifyNoInteractions(documentTags);
    }

    @Test
    void tagsForIndexing_onlyReadyDocs() {
        UUID pending = UUID.randomUUID();
        Tag a = tag("a");
        when(documents.findReadyIds(eq(userId), anyCollection())).thenReturn(List.of(docId));
        when(documentTags.findTagsForDocuments(eq(userId), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[] {docId, a}));

        Map<UUID, List<Tag>> result = service.tagsForIndexing(userId, Set.of(docId, pending));

        assertThat(result).containsOnlyKeys(docId);
        assertThat(result.get(docId)).containsExactly(a);
    }

    @Test
    void tagsForIndexing_noReadyDocs_isEmpty() {
        when(documents.findReadyIds(eq(userId), anyCollection())).thenReturn(List.of());

        assertThat(service.tagsForIndexing(userId, Set.of(docId))).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Tag tag(String name) {
        return new Tag(UUID.randomUUID(), userId, name, null);
    }

    private Document document(UUID id) {
        return new Document(id, userId, null, "f.pdf", "application/pdf", 1, "k/" + id, "a".repeat(64));
    }

    private void givenDocument(UUID id) {
        when(documents.findByIdAndUserId(id, userId)).thenReturn(Optional.of(document(id)));
    }

    private void givenDocumentTagRows(UUID documentId, Tag... rows) {
        List<Object[]> list = new ArrayList<>();
        for (Tag t : rows) list.add(new Object[] {documentId, t});
        when(documentTags.findTagsForDocuments(eq(userId), anyCollection())).thenReturn(list);
    }

    private DocumentTagsChangedEvent capturedEvent() {
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(cap.capture());
        assertThat(cap.getValue()).isInstanceOf(DocumentTagsChangedEvent.class);
        return (DocumentTagsChangedEvent) cap.getValue();
    }

    private void verifyNoEvent() {
        verify(events, never()).publishEvent(any(Object.class));
    }
}
