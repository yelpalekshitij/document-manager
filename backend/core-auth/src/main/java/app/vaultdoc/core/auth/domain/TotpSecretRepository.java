package app.vaultdoc.core.auth.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TotpSecretRepository extends JpaRepository<TotpSecret, UUID> {

    Optional<TotpSecret> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
