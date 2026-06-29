package app.vaultdoc.gateway.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TwoFactorCodeRequest(
        @NotBlank @Pattern(regexp = "\\d{6}") String code) {
}
