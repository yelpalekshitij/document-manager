package app.vaultdoc.core.auth.service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.vaultdoc.core.auth.config.VerificationProperties;
import app.vaultdoc.core.auth.domain.AccountStatus;
import app.vaultdoc.core.auth.domain.User;
import app.vaultdoc.core.auth.domain.UserRepository;
import app.vaultdoc.core.auth.domain.VerificationToken;
import app.vaultdoc.core.auth.domain.VerificationTokenRepository;
import app.vaultdoc.core.auth.mail.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerificationServiceTest {

    private VerificationTokenRepository tokens;
    private UserRepository users;
    private RecordingMailSender mail;
    private VerificationProperties props;
    private Instant now;
    private VerificationService service;

    @BeforeEach
    void setUp() {
        tokens = mock(VerificationTokenRepository.class);
        users = mock(UserRepository.class);
        mail = new RecordingMailSender();
        props = new VerificationProperties(
                Duration.ofHours(24),
                URI.create("http://localhost:4200"),
                "no-reply@vaultdoc.local",
                Duration.ofSeconds(60));
        now = Instant.parse("2026-05-14T12:00:00Z");
        service = new VerificationService(tokens, users, mail, props, Clock.fixed(now, ZoneOffset.UTC));

        when(tokens.save(any(VerificationToken.class))).thenAnswer(i -> i.getArgument(0));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void issue_on_registration_sends_email_with_verify_link() {
        User user = new User(UUID.randomUUID(), "k@example.com", "Kshitij", "hashed");

        service.issueOnRegistration(user);

        assertEquals(1, mail.sent.size());
        var msg = mail.sent.get(0);
        assertEquals("k@example.com", msg.to);
        assertEquals("Verify your VaultDoc email", msg.subject);
        assertNotNull(msg.body);
        // Body contains the verify URL pointing at the configured web base.
        org.junit.jupiter.api.Assertions.assertTrue(
                msg.body.contains("http://localhost:4200/verify?token="),
                "body should contain verify link; got:\n" + msg.body);
    }

    @Test
    void resend_throws_rate_limit_within_cooldown() {
        when(users.findByEmailIgnoreCase("k@example.com"))
                .thenReturn(Optional.of(pendingUser("k@example.com")));

        service.resend("k@example.com");
        assertEquals(1, mail.sent.size());

        // Same instant — should be rate-limited.
        VerificationRateLimitedException ex = assertThrows(
                VerificationRateLimitedException.class,
                () -> service.resend("k@example.com"));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getRetryAfter().toSeconds() > 0);
        assertEquals(1, mail.sent.size(), "no second email should be sent");
    }

    @Test
    void resend_does_not_send_if_account_already_active() {
        User activeUser = pendingUser("a@example.com");
        activeUser.setStatus(AccountStatus.ACTIVE);
        when(users.findByEmailIgnoreCase("a@example.com")).thenReturn(Optional.of(activeUser));

        service.resend("a@example.com");

        assertEquals(0, mail.sent.size());
    }

    @Test
    void resend_silent_for_unknown_email() {
        when(users.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

        // Should not throw — silence is the contract (no enumeration).
        service.resend("ghost@example.com");

        assertEquals(0, mail.sent.size());
    }

    @Test
    void consume_marks_token_used_and_activates_user() {
        User user = pendingUser("k@example.com");
        // Hash the token we'll present, so the repo returns the matching row.
        String raw = "test-raw-token";
        String hash = sha256(raw);
        VerificationToken existing = new VerificationToken(user.getId(), hash, now.plus(Duration.ofHours(1)));
        when(tokens.findByTokenHash(hash)).thenReturn(Optional.of(existing));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        User result = service.consume(raw);

        assertEquals(AccountStatus.ACTIVE, result.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(existing.getConsumedAt());
        verify(users, times(1)).save(user);
    }

    @Test
    void consume_rejects_unknown_token() {
        when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThrows(InvalidVerificationTokenException.class, () -> service.consume("nope"));
    }

    @Test
    void consume_rejects_expired_token() {
        User user = pendingUser("k@example.com");
        String raw = "expired";
        String hash = sha256(raw);
        VerificationToken existing = new VerificationToken(user.getId(), hash, now.minus(Duration.ofMinutes(1)));
        when(tokens.findByTokenHash(hash)).thenReturn(Optional.of(existing));

        assertThrows(InvalidVerificationTokenException.class, () -> service.consume(raw));
    }

    @Test
    void issue_revokes_previous_tokens() {
        User user = new User(UUID.randomUUID(), "k@example.com", null, "hash");
        service.issueOnRegistration(user);
        verify(tokens, times(1)).revokeAllForUser(eq(user.getId()));
    }

    private static User pendingUser(String email) {
        User u = new User(UUID.randomUUID(), email, null, "hash");
        u.setStatus(AccountStatus.PENDING_VERIFICATION);
        return u;
    }

    private static String sha256(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class RecordingMailSender implements EmailSender {
        record Sent(String to, String subject, String body) {
        }

        final List<Sent> sent = new ArrayList<>();

        @Override
        public void sendPlainText(String to, String subject, String body) {
            sent.add(new Sent(to, subject, body));
        }
    }
}
