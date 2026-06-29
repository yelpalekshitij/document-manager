package app.vaultdoc.ingest.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import app.vaultdoc.catalog.domain.Document;
import app.vaultdoc.catalog.domain.DocumentStatus;
import app.vaultdoc.catalog.service.DocumentNotFoundException;
import app.vaultdoc.catalog.service.DocumentService;
import app.vaultdoc.catalog.service.RegisterDocumentCommand;
import app.vaultdoc.core.storage.ObjectMetadata;
import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;
import app.vaultdoc.ingest.event.DocumentFinalizedEvent;
import app.vaultdoc.search.domain.DocumentIndexRecord;
import app.vaultdoc.search.service.SearchService;
import org.springframework.context.ApplicationEventPublisher;

public class IngestServiceImpl implements IngestService {

    private final DocumentService documents;
    private final ObjectStorage storage;
    private final SearchService search;
    private final VirusScanner scanner;
    private final MimeAllowlist mimeAllowlist;
    private final ApplicationEventPublisher eventPublisher;
    private final long maxSizeBytes;
    private final Duration presignPutTtl;

    public IngestServiceImpl(
            DocumentService documents,
            ObjectStorage storage,
            SearchService search,
            VirusScanner scanner,
            MimeAllowlist mimeAllowlist,
            ApplicationEventPublisher eventPublisher,
            long maxSizeBytes,
            Duration presignPutTtl) {
        this.documents = documents;
        this.storage = storage;
        this.search = search;
        this.scanner = scanner;
        this.mimeAllowlist = mimeAllowlist;
        this.eventPublisher = eventPublisher;
        this.maxSizeBytes = maxSizeBytes;
        this.presignPutTtl = presignPutTtl;
    }

    @Override
    @Transactional
    public UploadTicket initiateUpload(UUID userId, InitiateUploadCommand cmd) {
        if (cmd.sizeBytes() <= 0 || cmd.sizeBytes() > maxSizeBytes) {
            throw new SizeExceededException(cmd.sizeBytes(), maxSizeBytes);
        }
        if (!mimeAllowlist.isAllowed(cmd.mimeType())) {
            throw new MimeNotAllowedException(cmd.mimeType());
        }
        UUID documentId = UUID.randomUUID();
        StorageKey key = StorageKey.forDocument(userId, documentId, cmd.filename());
        Document registered = documents.register(userId, new RegisterDocumentCommand(
                documentId,
                cmd.folderId(),
                cmd.filename(),
                cmd.mimeType(),
                cmd.sizeBytes(),
                key.value(),
                cmd.sha256()));
        var uploadUrl = storage.presignPut(key, cmd.mimeType(), presignPutTtl);
        return new UploadTicket(registered.getId(), uploadUrl, key.value(), presignPutTtl.toSeconds());
    }

    @Override
    @Transactional
    public Document finalizeUpload(UUID userId, UUID documentId) {
        Document doc = documents.findByIdForUser(userId, documentId);
        if (doc.getStatus() != DocumentStatus.PENDING) {
            throw new DocumentAlreadyFinalizedException(documentId);
        }
        StorageKey key = new StorageKey(doc.getStorageKey());
        ObjectMetadata meta = storage.head(key)
                .orElseThrow(() -> new UploadObjectMissingException(documentId));

        if (meta.contentLength() != doc.getSizeBytes()) {
            documents.markFailed(documentId);
            throw new SizeMismatchException(doc.getSizeBytes(), meta.contentLength());
        }

        VirusScanner.Result scanResult = scanner.scan(key);
        if (scanResult == VirusScanner.Result.INFECTED) {
            documents.markFailed(documentId);
            storage.delete(key);
            throw new VirusDetectedException(documentId);
        }

        Document ready = documents.markReady(
                documentId,
                meta.contentType() == null ? doc.getMimeType() : meta.contentType(),
                meta.contentLength());

        // Index for search. Failure to index is a bug we want surfaced; let it propagate.
        search.index(new DocumentIndexRecord(
                ready.getId(),
                ready.getUserId(),
                ready.getOriginalFilename(),
                ready.getMimeType(),
                ready.getSizeBytes(),
                ready.getFolderId(),
                null,
                ready.getDocumentType(),
                List.of(),
                ready.getUploadedAt()));

        // Publish an in-process event that the AFTER_COMMIT forwarder relays to
        // the Redis Streams pipeline. Keeps Redis out of the request-handling tx.
        eventPublisher.publishEvent(new DocumentFinalizedEvent(
                ready.getId(),
                ready.getUserId(),
                ready.getMimeType(),
                ready.getStorageKey(),
                java.time.Instant.now()));

        return ready;
    }

    /** Convenience for callers that have only the doc id (e.g., delete) and need search cleanup. */
    @Transactional
    public void deindex(UUID userId, UUID documentId) {
        try {
            documents.findByIdForUser(userId, documentId);
        } catch (DocumentNotFoundException ignored) {
            // already gone — nothing to deindex
        }
        search.delete(userId, documentId);
    }
}
