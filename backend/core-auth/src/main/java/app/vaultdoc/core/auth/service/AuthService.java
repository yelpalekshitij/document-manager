package app.vaultdoc.core.auth.service;

import java.util.Optional;
import java.util.UUID;

import app.vaultdoc.core.auth.domain.User;

public interface AuthService {

    RegistrationResult register(RegisterCommand command);

    AuthenticatedSession login(LoginCommand command);

    AuthenticatedSession refresh(String refreshToken, String userAgent, String ipAddress);

    /**
     * Consume an email-verification token, mark the user ACTIVE, and return a
     * fresh authenticated session. Throws {@link InvalidVerificationTokenException}
     * if the token is unknown, expired, or already used.
     */
    AuthenticatedSession verifyEmail(String rawToken, String userAgent, String ipAddress);

    /**
     * Request a fresh verification email for the given address. Silent if the
     * address is unknown or already verified (no enumeration). Throws
     * {@link VerificationRateLimitedException} if called too soon.
     */
    void resendVerification(String email);

    void logout(String refreshToken);

    Optional<User> findById(UUID userId);
}
