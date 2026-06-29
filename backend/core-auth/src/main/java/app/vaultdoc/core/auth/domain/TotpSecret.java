package app.vaultdoc.core.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One row per user. The base32 TOTP secret is stored encrypted (AES-256-GCM)
 * so a raw DB dump yields no usable secrets.
 */
@Entity
@Table(name = "totp_secrets")
public class TotpSecret {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "secret_enc", nullable = false)
    private String secretEnc;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TotpSecret() { }

    public TotpSecret(UUID userId, String secretEnc) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.secretEnc = secretEnc;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public void enable() { this.enabled = true; }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getSecretEnc() { return secretEnc; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
