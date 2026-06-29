package app.vaultdoc.core.auth.passkey;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Result of {@link PasskeyService#beginRegistration}. {@code optionsJson} is
 * the W3C {@code PublicKeyCredentialCreationOptions} shape, ready to hand to
 * {@code navigator.credentials.create()} on the client after the standard
 * base64url ↔ ArrayBuffer dance.
 */
public record PasskeyRegistrationStarted(UUID challengeId, JsonNode optionsJson) {
}
