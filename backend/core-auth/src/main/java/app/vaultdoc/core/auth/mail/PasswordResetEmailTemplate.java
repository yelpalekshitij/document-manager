package app.vaultdoc.core.auth.mail;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class PasswordResetEmailTemplate {

    private PasswordResetEmailTemplate() { }

    public static String subject() {
        return "Reset your VaultDoc password";
    }

    public static String body(String displayName, String resetUrl) {
        String greeting = displayName == null || displayName.isBlank()
                ? "Hi," : "Hi " + displayName + ",";
        return """
                %s

                Someone requested a password reset for your VaultDoc account.
                If this was you, click the link below:

                  %s

                The link expires in 1 hour. If you did not request a reset,
                you can safely ignore this email — your password has not changed.

                — VaultDoc
                """.formatted(greeting, resetUrl);
    }

    public static String buildResetUrl(URI webBaseUrl, String rawToken) {
        String encoded = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        String base = webBaseUrl.toString();
        String sep = base.endsWith("/") ? "" : "/";
        return base + sep + "reset-password?token=" + encoded;
    }
}
