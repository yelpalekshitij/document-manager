package app.vaultdoc.gateway.auth.dto;

import app.vaultdoc.core.auth.twofa.BackupCodeStatus;

public record BackupCodesStatusResponse(long remaining, long total) {
    public static BackupCodesStatusResponse from(BackupCodeStatus s) {
        return new BackupCodesStatusResponse(s.remaining(), s.total());
    }
}
