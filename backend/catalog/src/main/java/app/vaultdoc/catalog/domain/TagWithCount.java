package app.vaultdoc.catalog.domain;

/** A tag plus the number of non-deleted documents carrying it. */
public record TagWithCount(Tag tag, long documentCount) {}
