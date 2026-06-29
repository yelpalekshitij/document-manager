package app.vaultdoc.core.auth.twofa;

public class InvalidFirstFactorTokenException extends RuntimeException {
    public InvalidFirstFactorTokenException() { super("First-factor token expired or invalid"); }
}
