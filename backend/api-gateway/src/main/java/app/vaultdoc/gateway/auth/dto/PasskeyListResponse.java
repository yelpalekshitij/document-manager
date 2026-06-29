package app.vaultdoc.gateway.auth.dto;

import java.util.List;

import app.vaultdoc.core.auth.passkey.PasskeySummary;

public record PasskeyListResponse(List<PasskeySummary> passkeys) {
}
