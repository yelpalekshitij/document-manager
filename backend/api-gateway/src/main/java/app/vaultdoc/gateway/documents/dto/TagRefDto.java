package app.vaultdoc.gateway.documents.dto;

import java.util.List;
import java.util.UUID;

import app.vaultdoc.catalog.domain.Tag;

/** A tag as embedded in document payloads (spec schema {@code TagRef}). */
public record TagRefDto(UUID id, String name, String color) {

    public static TagRefDto from(Tag t) {
        return new TagRefDto(t.getId(), t.getName(), t.getColor());
    }

    public static List<TagRefDto> from(List<Tag> tags) {
        return tags == null ? List.of() : tags.stream().map(TagRefDto::from).toList();
    }
}
