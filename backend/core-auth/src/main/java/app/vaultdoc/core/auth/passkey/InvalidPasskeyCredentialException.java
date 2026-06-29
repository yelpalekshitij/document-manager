package app.vaultdoc.core.auth.passkey;

public class InvalidPasskeyCredentialException extends RuntimeException {

    public InvalidPasskeyCredentialException(String message) {
        super(message);
    }

    public InvalidPasskeyCredentialException(String message, Throwable cause) {
        super(message, cause);
    }
}
