package app.vaultdoc.core.auth.passkey;

import java.util.List;
import java.util.UUID;

import app.vaultdoc.core.auth.service.AuthenticatedSession;
import tools.jackson.databind.JsonNode;

public interface PasskeyService {

    /**
     * Start a passkey registration for an already-authenticated user.
     * Caller is the controller, which has resolved the principal.
     */
    PasskeyRegistrationStarted beginRegistration(UUID userId);

    /**
     * Verify the attestation produced by the authenticator and persist the
     * credential. Returns a summary of the freshly-registered passkey.
     */
    PasskeySummary finishRegistration(UUID userId, UUID challengeId, String label, JsonNode credentialJson);

    /**
     * Start a passkey sign-in. The caller is not yet authenticated — the
     * credential the browser returns carries the user handle.
     */
    PasskeySigninStarted beginSignin();

    /**
     * Verify the assertion, identify the user, advance the credential
     * counter, and issue an auth session.
     */
    AuthenticatedSession finishSignin(UUID challengeId, JsonNode assertionJson, String userAgent, String ipAddress);

    List<PasskeySummary> list(UUID userId);

    void delete(UUID userId, UUID passkeyId);
}
