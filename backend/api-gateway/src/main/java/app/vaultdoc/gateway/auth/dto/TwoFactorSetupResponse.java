package app.vaultdoc.gateway.auth.dto;

import app.vaultdoc.core.auth.twofa.TwoFactorSetupStarted;

public record TwoFactorSetupResponse(String otpauthUri, String accountName) {
    public static TwoFactorSetupResponse from(TwoFactorSetupStarted s) {
        return new TwoFactorSetupResponse(s.otpauthUri(), s.accountName());
    }
}
