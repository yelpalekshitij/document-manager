package app.vaultdoc.gateway.auth.dto;

import java.util.UUID;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record PasskeyRegistrationRequest(
        @NotNull UUID challengeId,
        String label,
        @NotNull JsonNode credential) {
}
