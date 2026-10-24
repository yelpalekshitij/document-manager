package app.vaultdoc.catalog.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.vaultdoc.catalog.domain.Tag;
import app.vaultdoc.catalog.domain.TagWithCount;

/**
 * Tag CRUD + document↔tag assignment. Every call is user-scoped: a tag or
 * document owned by someone else behaves exactly like one that does not exist.
 *
 * <p>Whenever the tags visible on a document change, the implementation
 * publishes a {@link app.vaultdoc.catalog.event.DocumentTagsChangedEvent} so the
 * search index can follow after commit.
 */
public interface TagService {

    int MAX_TAGS_PER_USER = 500;
    int MAX_TAGS_PER_DOCUMENT = 50;

    /** All of the user's tags, sorted case-insensitively by name, with non-deleted document counts. */
    List<TagWithCount> listForUser(UUID userId);

    Tag create(UUID userId, String name, String color);

    /**
     * {@code null} name or colour leaves that field unchanged; an empty colour
     * clears it. Renaming re-indexes every document carrying the tag.
     */
    Tag update(UUID userId, UUID tagId, String newName, String color);

    /** Non-deleted documents carrying the tag. */
    long documentCount(UUID userId, UUID tagId);

    /** Detaches the tag from every document (FK cascade) and re-indexes them. */
    void delete(UUID userId, UUID tagId);

    /** Replace the document's tags with exactly {@code tagIds}. Returns the resulting tags. */
    List<Tag> setDocumentTags(UUID userId, UUID documentId, Collection<UUID> tagIds);

    /** Idempotent attach. Returns the document's resulting tags. */
    List<Tag> addDocumentTag(UUID userId, UUID documentId, UUID tagId);

    /** Idempotent detach. Returns the document's resulting tags. */
    List<Tag> removeDocumentTag(UUID userId, UUID documentId, UUID tagId);

    /** Tags per document for a page of documents; documents without tags map to an empty list. */
    Map<UUID, List<Tag>> tagsForDocuments(UUID userId, Collection<UUID> documentIds);

    /**
     * Search-sync view: like {@link #tagsForDocuments} but only for documents
     * that are READY (i.e. present in the search index).
     */
    Map<UUID, List<Tag>> tagsForIndexing(UUID userId, Collection<UUID> documentIds);
}
