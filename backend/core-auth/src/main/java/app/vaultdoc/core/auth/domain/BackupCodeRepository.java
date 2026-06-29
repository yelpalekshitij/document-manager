package app.vaultdoc.core.auth.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BackupCodeRepository extends JpaRepository<BackupCode, UUID> {

    List<BackupCode> findAllByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM BackupCode b WHERE b.userId = :userId")
    void deleteAllByUserId(UUID userId);

    long countByUserIdAndUsedAtIsNull(UUID userId);

    long countByUserId(UUID userId);
}
