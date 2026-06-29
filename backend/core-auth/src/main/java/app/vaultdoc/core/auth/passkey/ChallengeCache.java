package app.vaultdoc.core.auth.passkey;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores short-lived WebAuthn challenges between the begin and finish calls
 * of a single ceremony. Implementations must enforce a TTL so leaked
 * challenges expire automatically, and consumption (read+delete) must be
 * atomic so a challenge cannot be replayed.
 */
public interface ChallengeCache {

    enum Flow {
        REGISTRATION,
        SIGNIN,
    }

    record StoredChallenge(byte[] challenge, UUID userId) {
    }

    /** Returns the challenge ID the client echoes back. */
    UUID store(Flow flow, byte[] challenge, UUID userId, Duration ttl);

    /** Atomically read + delete the challenge. Empty if expired or never existed. */
    Optional<StoredChallenge> consume(Flow flow, UUID challengeId);
}
