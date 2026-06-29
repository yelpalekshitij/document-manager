package app.vaultdoc.core.auth.twofa;

public class TwoFactorNotEnabledException extends RuntimeException {
    public TwoFactorNotEnabledException() { super("2FA is not enabled for this account"); }
}
