package app.vaultdoc.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TagIndexResyncRepository extends JpaRepository<TagIndexResync, UUID> {

    /**
     * Insert, or bump an existing row's generation and make it due now. The
     * join on {@code documents} keeps it tenant-scoped: ids that aren't the
     * user's (or no longer exist) are silently skipped.
     */
    @Modifying
    @Query(value = """
            insert into tag_index_resync (document_id, user_id, last_error)
            select d.id, d.user_id, :error from documents d
            where d.user_id = :userId and d.id in :documentIds
            on conflict (document_id) do update
               set generation = tag_index_resync.generation + 1,
                   next_attempt_at = now(),
                   last_error = excluded.last_error
            """, nativeQuery = true)
    int enqueue(
            @Param("userId") UUID userId,
            @Param("documentIds") Collection<UUID> documentIds,
            @Param("error") String error);

    @Query(value = """
            select * from tag_index_resync
            where next_attempt_at <= now()
            order by next_attempt_at, document_id
            limit :limit
            """, nativeQuery = true)
    List<TagIndexResync> findDue(@Param("limit") int limit);

    /** Only the generation that was synced: a failure re-queued meanwhile survives. */
    @Modifying
    @Query(value = "delete from tag_index_resync where document_id = :documentId and generation = :generation",
            nativeQuery = true)
    int deleteIfGeneration(@Param("documentId") UUID documentId, @Param("generation") long generation);

    /** Exponential backoff: 1, 2, 4 … minutes, capped at an hour. */
    @Modifying
    @Query(value = """
            update tag_index_resync
               set attempts = attempts + 1,
                   last_error = :error,
                   next_attempt_at = now() + least(interval '1 minute' * power(2, least(attempts, 6)), interval '1 hour')
             where document_id in :documentIds
            """, nativeQuery = true)
    int recordFailure(@Param("documentIds") Collection<UUID> documentIds, @Param("error") String error);
}
