package app.vaultdoc.catalog.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "folder_id")
    private UUID folderId;

    @Column(name = "original_filename", nullable = false, length = 500)
    private String originalFilename;

    @Column(name = "mime_type", nullable = false, length = 120)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, unique = true, length = 1000)
    private String storageKey;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Column(name = "document_type", length = 64)
    private String documentType;

    @Column(name = "issued_at")
    private LocalDate issuedAt;

    @Column(name = "expires_at")
    private LocalDate expiresAt;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "ocr_status", nullable = false, length = 20)
    private OcrStatus ocrStatus = OcrStatus.PENDING;

    @Column(name = "ocr_text", columnDefinition = "TEXT")
    private String ocrText;

    @Column(name = "ocr_page_count")
    private Integer ocrPageCount;

    @Column(name = "ocr_completed_at")
    private Instant ocrCompletedAt;

    @Column(name = "ocr_failure_reason", columnDefinition = "TEXT")
    private String ocrFailureReason;

    protected Document() {}

    public Document(
            UUID id,
            UUID userId,
            UUID folderId,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            String storageKey,
            String sha256) {
        this.id = id;
        this.userId = userId;
        this.folderId = folderId;
        this.originalFilename = originalFilename;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.sha256 = sha256;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (uploadedAt == null) uploadedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getFolderId() { return folderId; }
    public String getOriginalFilename() { return originalFilename; }
    public String getMimeType() { return mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStorageKey() { return storageKey; }
    public String getSha256() { return sha256; }
    public DocumentStatus getStatus() { return status; }
    public String getDocumentType() { return documentType; }
    public LocalDate getIssuedAt() { return issuedAt; }
    public LocalDate getExpiresAt() { return expiresAt; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Instant getFinalizedAt() { return finalizedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public OcrStatus getOcrStatus() { return ocrStatus; }
    public String getOcrText() { return ocrText; }
    public Integer getOcrPageCount() { return ocrPageCount; }
    public Instant getOcrCompletedAt() { return ocrCompletedAt; }
    public String getOcrFailureReason() { return ocrFailureReason; }

    public void applyOcr(OcrStatus status, String text, Integer pageCount, String failureReason) {
        this.ocrStatus = status;
        this.ocrText = text;
        this.ocrPageCount = pageCount;
        this.ocrFailureReason = failureReason;
        if (status == OcrStatus.DONE || status == OcrStatus.SKIPPED || status == OcrStatus.FAILED) {
            this.ocrCompletedAt = Instant.now();
        }
    }

    public void markOcrProcessing() {
        this.ocrStatus = OcrStatus.PROCESSING;
        this.ocrFailureReason = null;
    }

    public void markReady() {
        if (status != DocumentStatus.PENDING) {
            throw new IllegalStateException("Cannot mark READY from status " + status);
        }
        this.status = DocumentStatus.READY;
        this.finalizedAt = Instant.now();
    }

    public void markFailed() {
        this.status = DocumentStatus.FAILED;
    }

    public void markDeleted() {
        this.status = DocumentStatus.DELETED;
    }

    public void moveTo(UUID folderId) {
        this.folderId = folderId;
    }

    public void updateMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public void updateSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }
}
