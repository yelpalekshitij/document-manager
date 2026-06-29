package app.vaultdoc.core.auth.twofa;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores the opaque first-factor token issued after a successful password check
 * when 2FA is enabled. The token is single-use and short-lived. A client must
 * present it together with a TOTP code (or backup code) to obtain a full session.
 */
public interface FirstFactorCache {

    /** Returns the raw token string the client will send back. */
    String store(UUID userId, Duration ttl);

    /** Atomically read-and-delete. Empty if expired or not found. */
    Optional<UUID> consume(String rawToken);
}
