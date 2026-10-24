package app.vaultdoc.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentTagRepository extends JpaRepository<DocumentTag, DocumentTag.Key> {

    /** Idempotent attach: a concurrent duplicate is a no-op rather than a PK violation. */
    @Modifying
    @Query(value = """
            insert into document_tags (document_id, tag_id) values (:documentId, :tagId)
            on conflict do nothing
            """, nativeQuery = true)
    int attach(@Param("documentId") UUID documentId, @Param("tagId") UUID tagId);

    @Modifying
    @Query("delete from DocumentTag dt where dt.documentId = :documentId and dt.tagId = :tagId")
    int detach(@Param("documentId") UUID documentId, @Param("tagId") UUID tagId);

    @Query("select dt.tagId from DocumentTag dt where dt.documentId = :documentId")
    List<UUID> findTagIdsByDocumentId(@Param("documentId") UUID documentId);

    @Query("select dt.documentId from DocumentTag dt where dt.tagId = :tagId")
    List<UUID> findDocumentIdsByTagId(@Param("tagId") UUID tagId);

    /**
     * Tags for a batch of documents, user-scoped on the tag side, ordered the
     * same way the tag list is. Each row is {@code [documentId, Tag]}.
     */
    @Query("""
            select dt.documentId, t
            from DocumentTag dt, Tag t
            where t.id = dt.tagId
              and t.userId = :userId
              and dt.documentId in :documentIds
            order by lower(t.name), t.id
            """)
    List<Object[]> findTagsForDocuments(
            @Param("userId") UUID userId,
            @Param("documentIds") Collection<UUID> documentIds);
}
