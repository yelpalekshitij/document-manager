package app.vaultdoc.core.auth.passkey;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record PasskeySigninStarted(UUID challengeId, JsonNode optionsJson) {
}
