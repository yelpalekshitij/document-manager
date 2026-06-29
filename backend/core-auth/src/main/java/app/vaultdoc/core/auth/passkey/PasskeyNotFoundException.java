package app.vaultdoc.core.auth.passkey;

public class PasskeyNotFoundException extends RuntimeException {

    public PasskeyNotFoundException() {
        super("Passkey not found");
    }
}
