package app.vaultdoc.core.auth.twofa;

public class InvalidTotpCodeException extends RuntimeException {
    public InvalidTotpCodeException() { super("Invalid or expired TOTP code"); }
}
