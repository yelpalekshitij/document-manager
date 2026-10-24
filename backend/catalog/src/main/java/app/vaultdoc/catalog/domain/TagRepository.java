package app.vaultdoc.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    Optional<Tag> findByIdAndUserId(UUID id, UUID userId);

    List<Tag> findAllByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    long countByUserId(UUID userId);

    /** Same {@code LOWER()} the unique index uses, so the pre-check and the index agree. */
    @Query("select t from Tag t where t.userId = :userId and lower(t.name) = lower(:name)")
    Optional<Tag> findByUserIdAndNameIgnoreCase(@Param("userId") UUID userId, @Param("name") String name);

    @Query("""
            select count(d) from DocumentTag dt, Document d
            where d.id = dt.documentId
              and dt.tagId = :tagId
              and d.status <> 'DELETED'
            """)
    long countLiveDocuments(@Param("tagId") UUID tagId);

    @Query("""
            select new app.vaultdoc.catalog.domain.TagWithCount(t, count(d.id))
            from Tag t
            left join DocumentTag dt on dt.tagId = t.id
            left join Document d on d.id = dt.documentId and d.status <> 'DELETED'
            where t.userId = :userId
            group by t
            order by lower(t.name), t.id
            """)
    List<TagWithCount> findAllWithCounts(@Param("userId") UUID userId);
}
