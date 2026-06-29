package app.vaultdoc.catalog.domain;

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

    @Query("""
            select d from Document d
            where d.userId = :userId
              and d.status = 'READY'
            order by d.uploadedAt desc
            """)
    List<Document> findRecentReady(@Param("userId") UUID userId, Pageable pageable);

    long countByUserIdAndFolderId(UUID userId, UUID folderId);
}
