package app.vaultdoc.gateway.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TwoFactorVerifyRequest(
        @NotBlank String twoFactorToken,
        @NotBlank @Pattern(regexp = "\\d{6}") String code) {
}
