package app.vaultdoc.core.auth.mail;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class VerificationEmailTemplate {

    private VerificationEmailTemplate() {
        // Utility.
    }

    public static String subject() {
        return "Verify your VaultDoc email";
    }

    public static String body(String recipientName, String verifyUrl) {
        String greeting = recipientName == null || recipientName.isBlank()
                ? "Welcome to VaultDoc."
                : "Hi " + recipientName + ",";
        return """
                %s

                Confirm your email to activate your VaultDoc account:

                  %s

                The link is good for 24 hours. If you didn't sign up for VaultDoc,
                ignore this email — no account will be created.

                — VaultDoc
                """.formatted(greeting, verifyUrl);
    }

    public static String buildVerifyUrl(URI webBaseUrl, String rawToken) {
        String encoded = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        String base = webBaseUrl.toString();
        String separator = base.endsWith("/") ? "" : "/";
        return base + separator + "verify?token=" + encoded;
    }
}
