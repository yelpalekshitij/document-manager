package app.vaultdoc.gateway.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record TwoFactorBackupRequest(
        @NotBlank String twoFactorToken,
        @NotBlank String backupCode) {
}
