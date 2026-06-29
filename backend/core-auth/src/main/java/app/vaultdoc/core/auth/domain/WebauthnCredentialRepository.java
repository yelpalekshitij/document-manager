package app.vaultdoc.core.auth.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebauthnCredentialRepository extends JpaRepository<WebauthnCredential, UUID> {

    Optional<WebauthnCredential> findByCredentialId(byte[] credentialId);

    List<WebauthnCredential> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<WebauthnCredential> findByIdAndUserId(UUID id, UUID userId);
}
