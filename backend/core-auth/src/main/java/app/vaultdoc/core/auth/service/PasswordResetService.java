package app.vaultdoc.core.auth.service;

public interface PasswordResetService {

    /**
     * Issue a password-reset email. Silent if the email is unknown or does not
     * belong to an active account (no enumeration). Rate-limited to one request
     * per {@code resendCooldown} per email address.
     *
     * @throws ResetRateLimitedException if called too soon after the previous request.
     */
    void issueResetLink(String email);

    /**
     * Validate the reset token, set the new password, and revoke all existing
     * refresh tokens so any active sessions are terminated.
     *
     * @throws InvalidResetTokenException if the token is unknown, expired, or already used.
     */
    void resetPassword(String rawToken, char[] newPassword);
}
