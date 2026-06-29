package app.vaultdoc.gateway.auth.dto;

import app.vaultdoc.core.auth.service.RegistrationResult;

public record RegistrationResponse(
        MeResponse user,
        boolean verificationRequired) {

    public static RegistrationResponse from(RegistrationResult r) {
        return new RegistrationResponse(
                new MeResponse(r.userId(), r.email(), r.displayName(), r.status(), r.createdAt()),
                r.verificationRequired());
    }
}
