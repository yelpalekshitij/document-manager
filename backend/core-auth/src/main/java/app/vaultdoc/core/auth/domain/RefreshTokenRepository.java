package app.vaultdoc.core.auth.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findAllBySessionId(UUID sessionId);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = CURRENT_TIMESTAMP "
            + "WHERE t.sessionId = :sessionId AND t.revokedAt IS NULL")
    int revokeSession(@Param("sessionId") UUID sessionId);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = CURRENT_TIMESTAMP "
            + "WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") UUID userId);
}
