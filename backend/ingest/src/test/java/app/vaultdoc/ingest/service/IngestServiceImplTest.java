package app.vaultdoc.ingest.service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.Document;
import app.vaultdoc.catalog.domain.DocumentStatus;
import app.vaultdoc.catalog.service.DocumentService;
import app.vaultdoc.catalog.service.RegisterDocumentCommand;
import app.vaultdoc.core.storage.ObjectMetadata;
import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;
import app.vaultdoc.search.service.SearchService;

class IngestServiceImplTest {

    private DocumentService documents;
    private ObjectStorage storage;
    private SearchService search;
    private VirusScanner scanner;
    private MimeAllowlist mimeAllowlist;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private IngestServiceImpl ingest;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        documents = mock(DocumentService.class);
        storage = mock(ObjectStorage.class);
        search = mock(SearchService.class);
        scanner = mock(VirusScanner.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        mimeAllowlist = new MimeAllowlist(List.of("application/pdf", "image/png"));
        ingest = new IngestServiceImpl(
                documents,
                storage,
                search,
                scanner,
                mimeAllowlist,
                eventPublisher,
                52_428_800L,
                Duration.ofMinutes(15));
    }

    @Test
    void initiate_rejectsDisallowedMime() {
        assertThatThrownBy(() -> ingest.initiateUpload(userId, new IngestService.InitiateUploadCommand(
                "evil.exe", "application/octet-stream", 100L, "a".repeat(64), null)))
                .isInstanceOf(MimeNotAllowedException.class);
    }

    @Test
    void initiate_rejectsOversize() {
        assertThatThrownBy(() -> ingest.initiateUpload(userId, new IngestService.InitiateUploadCommand(
                "huge.pdf", "application/pdf", 100_000_000L, "a".repeat(64), null)))
                .isInstanceOf(SizeExceededException.class);
    }

    @Test
    void initiate_happyPath_returnsPresignedUrl() {
        UUID docId = UUID.randomUUID();
        Document registered = newDoc(docId, "passport.pdf");
        when(documents.register(eq(userId), any(RegisterDocumentCommand.class))).thenReturn(registered);
        when(storage.presignPut(any(), eq("application/pdf"), any()))
                .thenReturn(URI.create("https://minio.example/put"));

        var ticket = ingest.initiateUpload(userId, new IngestService.InitiateUploadCommand(
                "passport.pdf", "application/pdf", 1024L, "a".repeat(64), null));

        assertThat(ticket.documentId()).isEqualTo(docId);
        assertThat(ticket.uploadUrl().toString()).startsWith("https://minio");
        ArgumentCaptor<RegisterDocumentCommand> cmd = ArgumentCaptor.forClass(RegisterDocumentCommand.class);
        verify(documents).register(eq(userId), cmd.capture());
        assertThat(cmd.getValue().storageKey()).contains("users/").contains("/docs/").contains("passport.pdf");
    }

    @Test
    void finalize_rejectsSizeMismatch() {
        UUID docId = UUID.randomUUID();
        Document doc = newDoc(docId, "passport.pdf");
        when(documents.findByIdForUser(userId, docId)).thenReturn(doc);
        when(storage.head(any())).thenReturn(Optional.of(new ObjectMetadata(
                "application/pdf", 999L, "etag", Instant.now())));

        assertThatThrownBy(() -> ingest.finalizeUpload(userId, docId))
                .isInstanceOf(SizeMismatchException.class);
        verify(documents).markFailed(docId);
        verify(documents, never()).markReady(any(), any(), anyLongHelper());
    }

    @Test
    void finalize_happyPath_indexesForSearch() {
        UUID docId = UUID.randomUUID();
        Document doc = newDoc(docId, "passport.pdf");
        when(documents.findByIdForUser(userId, docId)).thenReturn(doc);
        when(storage.head(any())).thenReturn(Optional.of(new ObjectMetadata(
                "application/pdf", 1024L, "etag", Instant.now())));
        when(scanner.scan(any())).thenReturn(VirusScanner.Result.CLEAN);
        when(documents.markReady(eq(docId), any(), anyLongHelper())).thenReturn(doc);

        Document result = ingest.finalizeUpload(userId, docId);

        assertThat(result).isSameAs(doc);
        verify(search, times(1)).index(any());
        verify(storage, never()).delete(any());
    }

    @Test
    void finalize_alreadyFinalized_throws() {
        UUID docId = UUID.randomUUID();
        Document doc = newDoc(docId, "passport.pdf");
        doc.markReady();
        when(documents.findByIdForUser(userId, docId)).thenReturn(doc);

        assertThatThrownBy(() -> ingest.finalizeUpload(userId, docId))
                .isInstanceOf(DocumentAlreadyFinalizedException.class);
        verify(storage, never()).head(any());
    }

    @Test
    void finalize_infectedFile_deletesBlobAndThrows() {
        UUID docId = UUID.randomUUID();
        Document doc = newDoc(docId, "passport.pdf");
        when(documents.findByIdForUser(userId, docId)).thenReturn(doc);
        when(storage.head(any())).thenReturn(Optional.of(new ObjectMetadata(
                "application/pdf", 1024L, "etag", Instant.now())));
        when(scanner.scan(any())).thenReturn(VirusScanner.Result.INFECTED);

        assertThatThrownBy(() -> ingest.finalizeUpload(userId, docId))
                .isInstanceOf(VirusDetectedException.class);
        verify(documents).markFailed(docId);
        verify(storage).delete(any(StorageKey.class));
    }

    private Document newDoc(UUID id, String filename) {
        return new Document(id, userId, null, filename, "application/pdf", 1024L,
                "users/" + userId + "/docs/" + id + "/" + filename, "a".repeat(64));
    }

    // Mockito's `anyLong()` static helper — extracted to keep imports tidy.
    private static long anyLongHelper() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
