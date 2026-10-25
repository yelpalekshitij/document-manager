package app.vaultdoc.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Lookup with no user-scope filter. For worker/consumer contexts where the
     * system processes every user's docs.
     */
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findByIdForSystem(@Param("id") UUID id);

    @Query("""
            select d from Document d
            where d.userId = :userId
              and d.status <> 'DELETED'
              and (:folderId is null or d.folderId = :folderId)
            order by d.uploadedAt desc
            """)
    Page<Document> pageByUser(
            @Param("userId") UUID userId,
            @Param("folderId") UUID folderId,
            Pageable pageable);

    /**
     * Tag filter with AND semantics: a document matches only when it carries
     * every one of {@code tagIds}. {@code tagCount} must equal the number of
     * <em>distinct</em> ids, or the HAVING clause never matches.
     */
    @Query(value = """
            select d from Document d
            where d.userId = :userId
              and d.status <> 'DELETED'
              and (:folderId is null or d.folderId = :folderId)
              and d.id in (
                  select dt.documentId from DocumentTag dt
                  where dt.tagId in :tagIds
                  group by dt.documentId
                  having count(dt.tagId) = :tagCount)
            order by d.uploadedAt desc
            """,
            countQuery = """
            select count(d) from Document d
            where d.userId = :userId
              and d.status <> 'DELETED'
              and (:folderId is null or d.folderId = :folderId)
              and d.id in (
                  select dt.documentId from DocumentTag dt
                  where dt.tagId in :tagIds
                  group by dt.documentId
                  having count(dt.tagId) = :tagCount)
            """)
    Page<Document> pageByUserAndTags(
            @Param("userId") UUID userId,
            @Param("folderId") UUID folderId,
            @Param("tagIds") Collection<UUID> tagIds,
            @Param("tagCount") long tagCount,
            Pageable pageable);

    @Query("""
            select d from Document d
            where d.userId = :userId
              and d.status = 'READY'
            order by d.uploadedAt desc
            """)
    List<Document> findRecentReady(@Param("userId") UUID userId, Pageable pageable);

    long countByUserIdAndFolderId(UUID userId, UUID folderId);

    /**
     * Row lock on the document for the rest of the transaction. Serialises tag
     * assignment per document, so the 50-tags cap holds under concurrent adds.
     */
    @Query(value = "select cast(id as varchar) from documents where id = :id for update", nativeQuery = true)
    Optional<String> lockForTagging(@Param("id") UUID id);

    /** Subset of {@code ids} that belong to the user and are READY (i.e. present in the search index). */
    @Query("""
            select d.id from Document d
            where d.userId = :userId
              and d.status = 'READY'
              and d.id in :ids
            """)
    List<UUID> findReadyIds(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);
}
