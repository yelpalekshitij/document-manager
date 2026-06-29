package app.vaultdoc.gateway.auth.dto;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record PasskeySigninOptionsResponse(UUID challengeId, JsonNode publicKey) {
}
