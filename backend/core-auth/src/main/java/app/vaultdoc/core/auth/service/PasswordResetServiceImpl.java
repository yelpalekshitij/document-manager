package app.vaultdoc.core.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import app.vaultdoc.core.auth.config.VerificationProperties;
import app.vaultdoc.core.auth.domain.AccountStatus;
import app.vaultdoc.core.auth.domain.PasswordResetToken;
import app.vaultdoc.core.auth.domain.PasswordResetTokenRepository;
import app.vaultdoc.core.auth.domain.RefreshTokenRepository;
import app.vaultdoc.core.auth.domain.User;
import app.vaultdoc.core.auth.domain.UserRepository;
import app.vaultdoc.core.auth.mail.EmailSender;
import app.vaultdoc.core.auth.mail.PasswordResetEmailTemplate;
import app.vaultdoc.core.auth.password.PasswordHasher;
import org.springframework.transaction.annotation.Transactional;

public class PasswordResetServiceImpl implements PasswordResetService {

    private static final int RAW_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Duration TOKEN_TTL = Duration.ofHours(1);

    private final PasswordResetTokenRepository tokens;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher hasher;
    private final EmailSender mail;
    private final VerificationProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, Instant> lastSendByEmail = new ConcurrentHashMap<>();

    public PasswordResetServiceImpl(
            PasswordResetTokenRepository tokens,
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordHasher hasher,
            EmailSender mail,
            VerificationProperties properties) {
        this(tokens, users, refreshTokens, hasher, mail, properties, Clock.systemUTC());
    }

    PasswordResetServiceImpl(
            PasswordResetTokenRepository tokens,
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordHasher hasher,
            EmailSender mail,
            VerificationProperties properties,
            Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.hasher = hasher;
        this.mail = mail;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void issueResetLink(String email) {
        String normalized = normalize(email);
        Instant now = Instant.now(clock);

        // Rate-limit check — always update timestamp to prevent timing oracle.
        Instant lastSent = lastSendByEmail.get(normalized);
        lastSendByEmail.put(normalized, now);
        if (lastSent != null) {
            Duration since = Duration.between(lastSent, now);
            if (since.compareTo(properties.resendCooldown()) < 0) {
                throw new ResetRateLimitedException(properties.resendCooldown().minus(since));
            }
        }

        // Silent on unknown / inactive accounts — no enumeration.
        users.findByEmailIgnoreCase(normalized).ifPresent(user -> {
            if (user.getStatus() != AccountStatus.ACTIVE) return;

            // Revoke any prior unconsumed token.
            tokens.revokeAllForUser(user.getId());

            byte[] raw = new byte[RAW_BYTES];
            RNG.nextBytes(raw);
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            Instant expiresAt = now.plus(TOKEN_TTL);
            tokens.save(new PasswordResetToken(user.getId(), sha256(rawToken), expiresAt));

            String resetUrl = PasswordResetEmailTemplate.buildResetUrl(
                    properties.webBaseUrl(), rawToken);
            mail.sendPlainText(
                    user.getEmail(),
                    PasswordResetEmailTemplate.subject(),
                    PasswordResetEmailTemplate.body(user.getDisplayName(), resetUrl));
        });
    }

    @Override
    @Transactional
    public void resetPassword(String rawToken, char[] newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidResetTokenException("Missing reset token");
        }
        PasswordResetToken token = tokens.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new InvalidResetTokenException("Unknown token"));

        if (!token.isConsumable(Instant.now(clock))) {
            throw new InvalidResetTokenException("Token expired or already used");
        }

        token.markConsumed();
        tokens.save(token);

        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new InvalidResetTokenException("User no longer exists"));

        user.setPasswordHash(hasher.hash(newPassword));
        users.save(user);

        // Revoke all active refresh tokens for the user — any stolen session is invalidated.
        refreshTokens.revokeAllForUser(user.getId());
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
