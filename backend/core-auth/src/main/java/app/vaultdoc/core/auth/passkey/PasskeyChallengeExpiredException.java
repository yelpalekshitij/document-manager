package app.vaultdoc.core.auth.passkey;

public class PasskeyChallengeExpiredException extends RuntimeException {

    public PasskeyChallengeExpiredException() {
        super("Passkey challenge expired or unknown");
    }
}
