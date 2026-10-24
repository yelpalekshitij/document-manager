package app.vaultdoc.catalog.domain;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Join row between a document and a tag. Mapped as an entity (rather than a
 * {@code @ManyToMany}) so JPQL can join on it without either side loading the
 * other — consistent with the "no JPA relationships" style of the catalog.
 * Writes go through {@link DocumentTagRepository}'s native
 * {@code ON CONFLICT DO NOTHING} insert so concurrent adds stay idempotent.
 */
@Entity
@Table(name = "document_tags")
@IdClass(DocumentTag.Key.class)
public class DocumentTag {

    @Id
    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Id
    @Column(name = "tag_id", nullable = false)
    private UUID tagId;

    protected DocumentTag() {}

    public DocumentTag(UUID documentId, UUID tagId) {
        this.documentId = documentId;
        this.tagId = tagId;
    }

    public UUID getDocumentId() { return documentId; }
    public UUID getTagId() { return tagId; }

    /** Composite primary key; field names must match the {@code @Id} fields above. */
    public static class Key implements Serializable {
        private UUID documentId;
        private UUID tagId;

        public Key() {}

        public Key(UUID documentId, UUID tagId) {
            this.documentId = documentId;
            this.tagId = tagId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && java.util.Objects.equals(documentId, k.documentId)
                    && java.util.Objects.equals(tagId, k.tagId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(documentId, tagId);
        }
    }
}
