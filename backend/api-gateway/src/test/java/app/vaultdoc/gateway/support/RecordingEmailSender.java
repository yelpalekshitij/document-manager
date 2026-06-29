package app.vaultdoc.gateway.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.vaultdoc.core.auth.mail.EmailSender;

/**
 * Test {@link EmailSender} that captures every message in memory. Integration
 * tests use it to recover the verification token from the rendered email body.
 *
 * <p>Override the production bean by importing this via
 * {@code @Import(RecordingEmailSender.AsBean.class)} on the test class — see
 * {@link RecordingEmailSender.AsBean}.
 */
public class RecordingEmailSender implements EmailSender {

    public record Sent(String to, String subject, String body) {
    }

    private static final Pattern TOKEN_IN_URL = Pattern.compile("verify\\?token=([\\w%\\-_.]+)");
    private static final Pattern RESET_TOKEN_IN_URL = Pattern.compile("reset-password\\?token=([\\w%\\-_.]+)");

    // Concurrent: integration-test classes share this bean (one cached context) and
    // run in parallel, so writes from one test must not corrupt another's captures.
    public final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void sendPlainText(String to, String subject, String body) {
        sent.add(new Sent(to, subject, body));
    }

    public Sent lastFor(String email) {
        return sent.stream()
                .filter(s -> s.to().equalsIgnoreCase(email))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No email captured for " + email));
    }

    public String extractTokenFromLast(String email) {
        Sent message = lastFor(email);
        Matcher matcher = TOKEN_IN_URL.matcher(message.body());
        if (!matcher.find()) {
            throw new AssertionError("No verify URL in email body:\n" + message.body());
        }
        return java.net.URLDecoder.decode(matcher.group(1), java.nio.charset.StandardCharsets.UTF_8);
    }

    public String extractResetToken(String email) {
        Sent message = lastFor(email);
        Matcher matcher = RESET_TOKEN_IN_URL.matcher(message.body());
        if (!matcher.find()) {
            throw new AssertionError("No reset URL in email body:\n" + message.body());
        }
        return java.net.URLDecoder.decode(matcher.group(1), java.nio.charset.StandardCharsets.UTF_8);
    }

    public void clear() {
        sent.clear();
    }

    @org.springframework.boot.test.context.TestConfiguration
    public static class AsBean {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        public RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }
}
