package app.vaultdoc.catalog.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import app.vaultdoc.catalog.domain.Document;
import app.vaultdoc.catalog.domain.DocumentRepository;
import app.vaultdoc.catalog.domain.FolderRepository;
import app.vaultdoc.catalog.domain.OcrStatus;

@Transactional
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documents;
    private final FolderRepository folders;
    private final DefaultFolderSeeder seeder;

    public DocumentServiceImpl(
            DocumentRepository documents,
            FolderRepository folders,
            DefaultFolderSeeder seeder) {
        this.documents = documents;
        this.folders = folders;
        this.seeder = seeder;
    }

    @Override
    public Document register(UUID userId, RegisterDocumentCommand cmd) {
        if (cmd.folderId() != null) {
            folders.findByIdAndUserId(cmd.folderId(), userId)
                    .orElseThrow(() -> new FolderNotFoundException(cmd.folderId()));
        }
        var doc = new Document(
                cmd.documentId(),
                userId,
                cmd.folderId(),
                cmd.originalFilename(),
                cmd.mimeType(),
                cmd.sizeBytes(),
                cmd.storageKey(),
                cmd.sha256());
        return documents.save(doc);
    }

    @Override
    @Transactional(readOnly = true)
    public Document findByIdForUser(UUID userId, UUID documentId) {
        return documents.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Document> list(UUID userId, UUID folderId, Set<UUID> tagIds, Pageable pageable) {
        seeder.ensureSeeded(userId);
        if (tagIds == null || tagIds.isEmpty()) {
            return documents.pageByUser(userId, folderId, pageable);
        }
        return documents.pageByUserAndTags(userId, folderId, tagIds, tagIds.size(), pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Document> recentReady(UUID userId, int limit) {
        return documents.findRecentReady(userId, PageRequest.of(0, Math.max(1, limit)));
    }

    @Override
    public Document markReady(UUID documentId, String confirmedMimeType, long confirmedSize) {
        var doc = documents.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (confirmedMimeType != null) doc.updateMimeType(confirmedMimeType);
        doc.updateSizeBytes(confirmedSize);
        doc.markReady();
        return documents.save(doc);
    }

    @Override
    public Document markFailed(UUID documentId) {
        var doc = documents.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        doc.markFailed();
        return documents.save(doc);
    }

    @Override
    @Transactional(readOnly = true)
    public Document findByIdForSystem(UUID documentId) {
        return documents.findByIdForSystem(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    @Override
    public Document markOcrProcessing(UUID documentId) {
        var doc = documents.findByIdForSystem(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        doc.markOcrProcessing();
        return documents.save(doc);
    }

    @Override
    public Document markOcr(UUID documentId, OcrStatus status, String text, Integer pageCount, String failureReason) {
        var doc = documents.findByIdForSystem(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        doc.applyOcr(status, text, pageCount, failureReason);
        return documents.save(doc);
    }

    @Override
    public void softDelete(UUID userId, UUID documentId) {
        var doc = documents.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        doc.markDeleted();
        documents.save(doc);
    }
}
