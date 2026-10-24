package app.vaultdoc.catalog.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.Document;
import app.vaultdoc.catalog.domain.DocumentRepository;
import app.vaultdoc.catalog.domain.DocumentStatus;
import app.vaultdoc.catalog.domain.Folder;
import app.vaultdoc.catalog.domain.FolderRepository;
import app.vaultdoc.catalog.domain.OcrStatus;

class DocumentServiceImplTest {

    private DocumentRepository documents;
    private FolderRepository folders;
    private DefaultFolderSeeder seeder;
    private DocumentServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final Pageable pageable = PageRequest.of(0, 10);
    private final Page<Document> empty = new PageImpl<>(List.of());

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        folders = mock(FolderRepository.class);
        seeder = mock(DefaultFolderSeeder.class);
        service = new DocumentServiceImpl(documents, folders, seeder);
        when(documents.pageByUser(any(), any(), any())).thenReturn(empty);
        when(documents.pageByUserAndTags(any(), any(), anyCollection(), anyLong(), any())).thenReturn(empty);
    }

    @Test
    void list_emptyTagFilter_usesUnfilteredQuery() {
        UUID folder = UUID.randomUUID();

        service.list(userId, folder, Set.of(), pageable);

        verify(seeder).ensureSeeded(userId);
        verify(documents).pageByUser(userId, folder, pageable);
        verify(documents, never()).pageByUserAndTags(any(), any(), anyCollection(), anyLong(), any());
    }

    @Test
    void list_nullTagFilter_isTreatedAsNoFilter() {
        service.list(userId, null, null, pageable);

        verify(documents).pageByUser(userId, null, pageable);
    }

    @Test
    void list_legacyOverload_isUnfiltered() {
        service.list(userId, null, pageable);

        verify(documents).pageByUser(userId, null, pageable);
    }

    @Test
    void list_withTagFilter_usesAndQueryWithDistinctCount() {
        UUID folder = UUID.randomUUID();
        Set<UUID> tagIds = new LinkedHashSet<>(List.of(UUID.randomUUID(), UUID.randomUUID()));

        service.list(userId, folder, tagIds, pageable);

        verify(seeder).ensureSeeded(userId);
        verify(documents).pageByUserAndTags(eq(userId), eq(folder), eq(tagIds), eq(2L), eq(pageable));
        verify(documents, never()).pageByUser(any(), any(), any());
    }

    // ── Characterisation of the pre-1.4 paths (catalog coverage bar) ────────

    @Test
    void register_withoutFolder_persistsWithGivenId() {
        when(documents.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID id = UUID.randomUUID();

        Document saved = service.register(userId, command(id, null));

        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(saved.getOcrStatus()).isEqualTo(OcrStatus.PENDING);
    }

    @Test
    void register_foreignFolder_notFound() {
        UUID folder = UUID.randomUUID();
        when(folders.findByIdAndUserId(folder, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(userId, command(UUID.randomUUID(), folder)))
                .isInstanceOf(FolderNotFoundException.class);
        verify(documents, never()).save(any());
    }

    @Test
    void register_ownFolder_persists() {
        UUID folder = UUID.randomUUID();
        when(folders.findByIdAndUserId(folder, userId))
                .thenReturn(Optional.of(new Folder(folder, userId, null, "F", null, null, 0, false)));
        when(documents.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.register(userId, command(UUID.randomUUID(), folder)).getFolderId()).isEqualTo(folder);
    }

    @Test
    void findByIdForUser_foundAndNotFound() {
        Document doc = document();
        when(documents.findByIdAndUserId(doc.getId(), userId)).thenReturn(Optional.of(doc));
        UUID missing = UUID.randomUUID();
        when(documents.findByIdAndUserId(missing, userId)).thenReturn(Optional.empty());

        assertThat(service.findByIdForUser(userId, doc.getId())).isSameAs(doc);
        assertThatThrownBy(() -> service.findByIdForUser(userId, missing))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining(missing.toString());
    }

    @Test
    void recentReady_clampsLimitToAtLeastOne() {
        when(documents.findRecentReady(eq(userId), any())).thenReturn(List.of());

        service.recentReady(userId, 0);

        verify(documents).findRecentReady(userId, PageRequest.of(0, 1));
    }

    @Test
    void markReady_updatesConfirmedMetadataAndStatus() {
        Document doc = document();
        when(documents.findById(doc.getId())).thenReturn(Optional.of(doc));
        when(documents.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        Document ready = service.markReady(doc.getId(), "image/png", 42);

        assertThat(ready.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(ready.getMimeType()).isEqualTo("image/png");
        assertThat(ready.getSizeBytes()).isEqualTo(42);
        assertThat(ready.getFinalizedAt()).isNotNull();
        // A second finalize is illegal.
        assertThatThrownBy(ready::markReady).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markReady_nullMime_keepsClaimedMime() {
        Document doc = document();
        when(documents.findById(doc.getId())).thenReturn(Optional.of(doc));
        when(documents.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.markReady(doc.getId(), null, 1).getMimeType()).isEqualTo("application/pdf");
    }

    @Test
    void markReady_andMarkFailed_unknownDocument_notFound() {
        UUID missing = UUID.randomUUID();
        when(documents.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markReady(missing, null, 1)).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> service.markFailed(missing)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void markFailed_setsFailed() {
        Document doc = document();
        when(documents.findById(doc.getId())).thenReturn(Optional.of(doc));
        when(documents.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.markFailed(doc.getId()).getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    void systemContext_ocrLifecycle() {
        Document doc = document();
        when(documents.findByIdForSystem(doc.getId())).thenReturn(Optional.of(doc));
        when(documents.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.findByIdForSystem(doc.getId())).isSameAs(doc);
        assertThat(service.markOcrProcessing(doc.getId()).getOcrStatus()).isEqualTo(OcrStatus.PROCESSING);

        Document done = service.markOcr(doc.getId(), OcrStatus.DONE, "text", 2, null);
        assertThat(done.getOcrText()).isEqualTo("text");
        assertThat(done.getOcrPageCount()).isEqualTo(2);
        assertThat(done.getOcrCompletedAt()).isNotNull();
        assertThat(done.getOcrFailureReason()).isNull();
    }

    @Test
    void systemContext_unknownDocument_notFound() {
        UUID missing = UUID.randomUUID();
        when(documents.findByIdForSystem(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByIdForSystem(missing)).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> service.markOcrProcessing(missing)).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> service.markOcr(missing, OcrStatus.FAILED, null, null, "x"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void softDelete_marksDeleted_andScopesToUser() {
        Document doc = document();
        when(documents.findByIdAndUserId(doc.getId(), userId)).thenReturn(Optional.of(doc));

        service.softDelete(userId, doc.getId());

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.DELETED);
        verify(documents).save(doc);

        UUID foreign = UUID.randomUUID();
        when(documents.findByIdAndUserId(foreign, userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.softDelete(userId, foreign)).isInstanceOf(DocumentNotFoundException.class);
    }

    private RegisterDocumentCommand command(UUID id, UUID folderId) {
        return new RegisterDocumentCommand(id, folderId, "f.pdf", "application/pdf", 10, "k/" + id, "a".repeat(64));
    }

    private Document document() {
        UUID id = UUID.randomUUID();
        return new Document(id, userId, null, "f.pdf", "application/pdf", 10, "k/" + id, "a".repeat(64));
    }
}
