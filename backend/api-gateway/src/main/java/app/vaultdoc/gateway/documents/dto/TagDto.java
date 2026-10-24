package app.vaultdoc.gateway.documents.dto;

import java.time.Instant;
import java.util.UUID;

import app.vaultdoc.catalog.domain.Tag;
import app.vaultdoc.catalog.domain.TagWithCount;

/** Spec schema {@code Tag}: a tag with its non-deleted document count. */
public record TagDto(
        UUID id,
        String name,
        String color,
        long documentCount,
        Instant createdAt) {

    public static TagDto from(Tag t, long documentCount) {
        return new TagDto(t.getId(), t.getName(), t.getColor(), documentCount, t.getCreatedAt());
    }

    public static TagDto from(TagWithCount row) {
        return from(row.tag(), row.documentCount());
    }
}
