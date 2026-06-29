package app.vaultdoc.core.auth.service;

public class InvalidResetTokenException extends RuntimeException {
    public InvalidResetTokenException(String message) { super(message); }
}
