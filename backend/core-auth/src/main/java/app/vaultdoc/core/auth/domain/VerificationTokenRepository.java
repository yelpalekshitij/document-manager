package app.vaultdoc.core.auth.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {

    Optional<VerificationToken> findByTokenHash(String tokenHash);

    /**
     * Marks any still-active token for this user as consumed.
     * Used when re-issuing — the previous email becomes useless immediately.
     */
    @Modifying
    @Query("UPDATE VerificationToken t SET t.consumedAt = CURRENT_TIMESTAMP "
            + "WHERE t.userId = :userId AND t.consumedAt IS NULL")
    int revokeAllForUser(@Param("userId") UUID userId);
}
