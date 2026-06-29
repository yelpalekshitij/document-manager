package app.vaultdoc.core.auth.twofa;

public class TwoFactorAlreadyEnabledException extends RuntimeException {
    public TwoFactorAlreadyEnabledException() { super("2FA is already enabled"); }
}
