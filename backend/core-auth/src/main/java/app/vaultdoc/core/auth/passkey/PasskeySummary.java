package app.vaultdoc.core.auth.passkey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.vaultdoc.core.auth.domain.WebauthnCredential;

public record PasskeySummary(
        UUID id,
        String label,
        UUID aaguid,
        List<String> transports,
        Instant createdAt,
        Instant lastUsedAt) {

    public static PasskeySummary from(WebauthnCredential c) {
        return new PasskeySummary(
                c.getId(),
                c.getLabel(),
                c.getAaguid(),
                c.getTransports() == null ? List.of() : List.of(c.getTransports()),
                c.getCreatedAt(),
                c.getLastUsedAt());
    }
}
