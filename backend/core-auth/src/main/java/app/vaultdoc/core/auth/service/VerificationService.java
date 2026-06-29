package app.vaultdoc.core.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import app.vaultdoc.core.auth.config.VerificationProperties;
import app.vaultdoc.core.auth.domain.User;
import app.vaultdoc.core.auth.domain.UserRepository;
import app.vaultdoc.core.auth.domain.VerificationToken;
import app.vaultdoc.core.auth.domain.VerificationTokenRepository;
import app.vaultdoc.core.auth.mail.EmailSender;
import app.vaultdoc.core.auth.mail.VerificationEmailTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns verification-token issuance, verification, and resend-rate-limiting.
 *
 * <p>Storage: only the SHA-256 hash of the raw token is persisted. Rate limiter:
 * in-memory per-email last-sent timestamp — sufficient for a single instance;
 * moves to Redis when we scale.
 */
public class VerificationService {

    private static final int RAW_TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    private final VerificationTokenRepository tokens;
    private final UserRepository users;
    private final EmailSender mail;
    private final VerificationProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, Instant> lastSendByEmail = new ConcurrentHashMap<>();

    public VerificationService(
            VerificationTokenRepository tokens,
            UserRepository users,
            EmailSender mail,
            VerificationProperties properties) {
        this(tokens, users, mail, properties, Clock.systemUTC());
    }

    VerificationService(
            VerificationTokenRepository tokens,
            UserRepository users,
            EmailSender mail,
            VerificationProperties properties,
            Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.mail = mail;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Issue a verification email for a newly-registered user. Does not enforce
     * a cooldown — registration itself is the one-per-account event.
     */
    @Transactional
    public void issueOnRegistration(User user) {
        sendNewToken(user);
        lastSendByEmail.put(normalize(user.getEmail()), Instant.now(clock));
    }

    /**
     * Send a fresh verification email if the account exists and is unverified.
     * Always silent to outside observers (no leak of account existence).
     * Throws {@link VerificationRateLimitedException} if called too soon.
     */
    @Transactional
    public void resend(String email) {
        String normalized = normalize(email);
        Instant now = Instant.now(clock);
        Instant lastSent = lastSendByEmail.get(normalized);
        if (lastSent != null) {
            Duration sinceLast = Duration.between(lastSent, now);
            if (sinceLast.compareTo(properties.resendCooldown()) < 0) {
                throw new VerificationRateLimitedException(
                        properties.resendCooldown().minus(sinceLast));
            }
        }
        // Always update the timestamp to make timing observation harder.
        lastSendByEmail.put(normalized, now);

        Optional<User> userOpt = users.findByEmailIgnoreCase(normalized);
        if (userOpt.isEmpty()) return;
        User user = userOpt.get();
        if (user.getStatus() != app.vaultdoc.core.auth.domain.AccountStatus.PENDING_VERIFICATION) {
            return;
        }
        sendNewToken(user);
    }

    /**
     * Consume a token presented by the user. Marks the token consumed and
     * activates the user. Returns the user so the caller can issue tokens.
     */
    @Transactional
    public User consume(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidVerificationTokenException("Missing token");
        }
        String hash = sha256(rawToken);
        VerificationToken token = tokens.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidVerificationTokenException("Unknown token"));

        if (!token.isConsumable(Instant.now(clock))) {
            throw new InvalidVerificationTokenException("Token expired or already used");
        }

        token.markConsumed();
        tokens.save(token);

        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new InvalidVerificationTokenException("User no longer exists"));
        user.setStatus(app.vaultdoc.core.auth.domain.AccountStatus.ACTIVE);
        users.save(user);
        return user;
    }

    private void sendNewToken(User user) {
        // Revoke any older unconsumed token so the previous email becomes useless.
        tokens.revokeAllForUser(user.getId());

        byte[] raw = new byte[RAW_TOKEN_BYTES];
        RNG.nextBytes(raw);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = Instant.now(clock).plus(properties.tokenTtl());

        tokens.save(new VerificationToken(user.getId(), sha256(rawToken), expiresAt));

        String verifyUrl = VerificationEmailTemplate.buildVerifyUrl(properties.webBaseUrl(), rawToken);
        mail.sendPlainText(
                user.getEmail(),
                VerificationEmailTemplate.subject(),
                VerificationEmailTemplate.body(user.getDisplayName(), verifyUrl));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String sha256(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
