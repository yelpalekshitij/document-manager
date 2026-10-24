package app.vaultdoc.catalog.service;

import java.util.UUID;

public class TagNotFoundException extends RuntimeException {
    private final UUID tagId;

    public TagNotFoundException(UUID tagId) {
        super("Tag " + tagId + " not found");
        this.tagId = tagId;
    }

    public UUID getTagId() {
        return tagId;
    }
}
