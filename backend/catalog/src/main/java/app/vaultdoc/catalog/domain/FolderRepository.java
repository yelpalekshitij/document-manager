package app.vaultdoc.catalog.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FolderRepository extends JpaRepository<Folder, UUID> {

    List<Folder> findAllByUserIdOrderBySortOrderAscNameAsc(UUID userId);

    Optional<Folder> findByIdAndUserId(UUID id, UUID userId);

    @Query("select count(f) from Folder f where f.userId = :userId and f.system = true")
    long countSystemForUser(@Param("userId") UUID userId);

    @Modifying
    @Query("delete from Folder f where f.id = :id and f.userId = :userId")
    int deleteByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);
}
