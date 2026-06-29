package app.vaultdoc.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "folders")
public class Folder {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 16)
    private String color;

    @Column(length = 64)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Folder() {}

    public Folder(UUID id, UUID userId, UUID parentId, String name, String color, String icon,
                  int sortOrder, boolean system) {
        this.id = id;
        this.userId = userId;
        this.parentId = parentId;
        this.name = name;
        this.color = color;
        this.icon = icon;
        this.sortOrder = sortOrder;
        this.system = system;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getParentId() { return parentId; }
    public String getName() { return name; }
    public String getColor() { return color; }
    public String getIcon() { return icon; }
    public int getSortOrder() { return sortOrder; }
    public boolean isSystem() { return system; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void rename(String name) { this.name = name; }
    public void moveTo(UUID parentId) { this.parentId = parentId; }
    public void setColor(String color) { this.color = color; }
    public void setIcon(String icon) { this.icon = icon; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
