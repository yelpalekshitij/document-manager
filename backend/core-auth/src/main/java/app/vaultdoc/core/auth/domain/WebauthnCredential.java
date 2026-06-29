package app.vaultdoc.core.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "webauthn_credentials")
public class WebauthnCredential {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "credential_id", nullable = false, unique = true)
    private byte[] credentialId;

    @Column(name = "attestation_data", nullable = false)
    private byte[] attestationData;

    @Column(name = "sign_count", nullable = false)
    private long signCount;

    @Column(name = "aaguid")
    private UUID aaguid;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "transports", columnDefinition = "text[]")
    private String[] transports = new String[0];

    @Column(name = "attestation_fmt")
    private String attestationFormat;

    @Column(name = "backup_eligible", nullable = false)
    private boolean backupEligible;

    @Column(name = "backup_state", nullable = false)
    private boolean backupState;

    @Column(name = "user_handle", nullable = false)
    private byte[] userHandle;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected WebauthnCredential() {
        // JPA only.
    }

    public WebauthnCredential(
            UUID userId,
            byte[] credentialId,
            byte[] attestationData,
            long signCount,
            UUID aaguid,
            String[] transports,
            String attestationFormat,
            boolean backupEligible,
            boolean backupState,
            byte[] userHandle,
            String label) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.credentialId = credentialId;
        this.attestationData = attestationData;
        this.signCount = signCount;
        this.aaguid = aaguid;
        this.transports = transports == null ? new String[0] : transports;
        this.attestationFormat = attestationFormat;
        this.backupEligible = backupEligible;
        this.backupState = backupState;
        this.userHandle = userHandle;
        this.label = label;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public void markUsed(long newSignCount) {
        // Only advance the stored counter — never go backward. Apple authenticators
        // always send 0, so we tolerate non-monotonic counters by clamping.
        if (newSignCount > this.signCount) {
            this.signCount = newSignCount;
        }
        this.lastUsedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public byte[] getCredentialId() { return credentialId; }
    public byte[] getAttestationData() { return attestationData; }
    public long getSignCount() { return signCount; }
    public UUID getAaguid() { return aaguid; }
    public String[] getTransports() { return transports; }
    public String getAttestationFormat() { return attestationFormat; }
    public boolean isBackupEligible() { return backupEligible; }
    public boolean isBackupState() { return backupState; }
    public byte[] getUserHandle() { return userHandle; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
}
