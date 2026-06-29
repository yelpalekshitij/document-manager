package app.vaultdoc.core.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One row per backup code. Each code is stored as an Argon2id hash — the raw
 * code is shown to the user once and never persisted in plaintext.
 */
@Entity
@Table(name = "backup_codes")
public class BackupCode {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BackupCode() { }

    public BackupCode(UUID userId, String codeHash) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.codeHash = codeHash;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isUsed() { return usedAt != null; }

    public void markUsed() {
        if (usedAt == null) usedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getCodeHash() { return codeHash; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
