package app.vaultdoc.core.auth.twofa;

/**
 * Thrown by {@link app.vaultdoc.core.auth.service.AuthServiceImpl#login} when
 * 2FA is enabled. The {@code firstFactorToken} field carries the opaque token
 * the controller returns to the client as a {@code TwoFactorChallengeResponse}.
 *
 * <p>Keeping this as an exception (rather than a sealed return type on the service
 * interface) means the {@link app.vaultdoc.core.auth.service.AuthService} interface
 * signature stays stable — only the controller needs to know about the 2FA protocol.
 */
public class TwoFactorRequiredException extends RuntimeException {

    private final String firstFactorToken;

    public TwoFactorRequiredException(String firstFactorToken) {
        super("2FA verification required");
        this.firstFactorToken = firstFactorToken;
    }

    public String getFirstFactorToken() {
        return firstFactorToken;
    }
}
