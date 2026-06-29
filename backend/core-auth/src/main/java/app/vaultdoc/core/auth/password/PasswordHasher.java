package app.vaultdoc.core.auth.password;

public interface PasswordHasher {

    /**
     * Encode a plain-text password into a self-describing hash (algorithm, params, salt all embedded).
     * Throws if the input is null or empty.
     */
    String hash(char[] plaintext);

    /**
     * Verify a plain-text password against a previously {@link #hash(char[])}-produced encoding.
     * Returns false (never throws) if {@code encoded} is malformed.
     */
    boolean verify(char[] plaintext, String encoded);
}
