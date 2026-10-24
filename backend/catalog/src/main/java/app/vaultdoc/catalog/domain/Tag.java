package app.vaultdoc.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A user-owned label. Names are unique per user case-insensitively (enforced by
 * the {@code tags_unique_name} index on {@code (user_id, LOWER(name))}).
 */
@Entity
@Table(name = "tags")
public class Tag {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 16)
    private String color;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Tag() {}

    public Tag(UUID id, UUID userId, String name, String color) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.color = color;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public String getColor() { return color; }
    public Instant getCreatedAt() { return createdAt; }

    public void rename(String name) { this.name = name; }
    public void setColor(String color) { this.color = color; }
}
