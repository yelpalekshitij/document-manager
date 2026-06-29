package app.vaultdoc.core.auth.twofa;

import java.util.List;
import java.util.UUID;

import app.vaultdoc.core.auth.service.AuthenticatedSession;

public interface TwoFactorService {

    /** Returns true if 2FA is enabled for the given user. */
    boolean isEnabled(UUID userId);

    /** Generate a fresh TOTP secret and store it (disabled). Email label fetched from user record. */
    TwoFactorSetupStarted beginSetup(UUID userId);

    /**
     * Verify the first TOTP code to confirm the app is working, enable 2FA,
     * and generate 10 single-use backup codes. Returns the raw backup codes
     * (shown once — never retrievable again after this call).
     */
    List<String> finishSetup(UUID userId, String totpCode);

    /**
     * Verify a TOTP code presented during the 2FA login step. Consumes the
     * first-factor token. Returns a full session.
     */
    AuthenticatedSession verifyTotp(
            String firstFactorToken, String totpCode, String userAgent, String ipAddress);

    /**
     * Verify a backup code presented during the 2FA login step. Consumes both
     * the first-factor token and the backup code. Returns a full session.
     */
    AuthenticatedSession verifyBackupCode(
            String firstFactorToken, String rawBackupCode, String userAgent, String ipAddress);

    BackupCodeStatus getBackupCodeStatus(UUID userId);

    /** Regenerate backup codes — requires the current TOTP code as confirmation. */
    List<String> regenerateBackupCodes(UUID userId, String totpCode);

    /** Disable 2FA — requires the current TOTP code as confirmation. */
    void disable(UUID userId, String totpCode);

    /**
     * Issue a short-lived first-factor token to be returned to the client as a
     * {@code TwoFactorChallengeResponse}. Called by {@link app.vaultdoc.core.auth.service.AuthServiceImpl}
     * when the password check passes but 2FA is enabled.
     */
    String issueFirstFactorToken(UUID userId);
}
