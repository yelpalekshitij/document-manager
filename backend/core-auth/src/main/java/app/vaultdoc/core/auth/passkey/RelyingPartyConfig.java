package app.vaultdoc.core.auth.passkey;

import java.util.List;

/**
 * Runtime view of the WebAuthn Relying Party identity. The browser binds
 * issued credentials to {@link #rpId()} — switching it later invalidates all
 * existing credentials, so prod/dev must use stable values from day 1.
 */
public record RelyingPartyConfig(
        String rpId,
        String rpName,
        List<String> origins) {
}
