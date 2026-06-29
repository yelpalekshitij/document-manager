package app.vaultdoc.core.auth.twofa;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import app.vaultdoc.core.auth.domain.BackupCode;
import app.vaultdoc.core.auth.domain.BackupCodeRepository;
import app.vaultdoc.core.auth.domain.TotpSecret;
import app.vaultdoc.core.auth.domain.TotpSecretRepository;
import app.vaultdoc.core.auth.domain.User;
import app.vaultdoc.core.auth.domain.UserRepository;
import app.vaultdoc.core.auth.password.PasswordHasher;
import app.vaultdoc.core.auth.service.AuthenticatedSession;
import app.vaultdoc.core.auth.service.InvalidCredentialsException;
import app.vaultdoc.core.auth.token.JwtIssuer;
import app.vaultdoc.core.auth.token.RefreshTokenService;
import app.vaultdoc.core.auth.token.RefreshTokenService.IssueResult;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.transaction.annotation.Transactional;

public class TwoFactorServiceImpl implements TwoFactorService {

    private static final int BACKUP_CODE_COUNT = 10;
    private static final int BACKUP_CODE_LENGTH = 9;   // stored as XXX-XXX-XXX
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();

    private final TotpSecretRepository secrets;
    private final BackupCodeRepository backupCodes;
    private final UserRepository users;
    private final PasswordHasher hasher;
    private final JwtIssuer jwt;
    private final RefreshTokenService refreshTokens;
    private final TotpSecretEncryptor encryptor;
    private final FirstFactorCache firstFactorCache;
    private final Duration tokenTtl;
    private final String issuer;

    private final DefaultSecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final DefaultCodeVerifier codeVerifier;

    public TwoFactorServiceImpl(
            TotpSecretRepository secrets,
            BackupCodeRepository backupCodes,
            UserRepository users,
            PasswordHasher hasher,
            JwtIssuer jwt,
            RefreshTokenService refreshTokens,
            TotpSecretEncryptor encryptor,
            FirstFactorCache firstFactorCache,
            Duration tokenTtl,
            String issuer) {
        this.secrets = secrets;
        this.backupCodes = backupCodes;
        this.users = users;
        this.hasher = hasher;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.encryptor = encryptor;
        this.firstFactorCache = firstFactorCache;
        this.tokenTtl = tokenTtl;
        this.issuer = issuer;

        DefaultCodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        this.codeVerifier = new DefaultCodeVerifier(generator, new SystemTimeProvider());
        this.codeVerifier.setAllowedTimePeriodDiscrepancy(1); // ±1 window
    }

    @Override
    public boolean isEnabled(UUID userId) {
        return secrets.findByUserId(userId)
                .map(TotpSecret::isEnabled)
                .orElse(false);
    }

    @Override
    @Transactional
    public TwoFactorSetupStarted beginSetup(UUID userId) {
        secrets.findByUserId(userId).ifPresent(s -> {
            if (s.isEnabled()) throw new TwoFactorAlreadyEnabledException();
            // Delete any pending (not yet enabled) secret so setup is idempotent.
            secrets.delete(s);
        });

        String accountEmail = users.findById(userId)
                .map(u -> u.getEmail())
                .orElse(userId.toString());

        String rawSecret = secretGenerator.generate();
        String encryptedSecret = encryptor.encrypt(rawSecret);
        secrets.save(new TotpSecret(userId, encryptedSecret));

        QrData qrData = new QrData.Builder()
                .label(accountEmail)
                .secret(rawSecret)
                .issuer(issuer)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();

        return new TwoFactorSetupStarted(qrData.getUri(), accountEmail);
    }

    @Override
    @Transactional
    public List<String> finishSetup(UUID userId, String totpCode) {
        TotpSecret secret = secrets.findByUserId(userId)
                .orElseThrow(InvalidTotpCodeException::new);
        if (secret.isEnabled()) throw new TwoFactorAlreadyEnabledException();

        String rawSecret = encryptor.decrypt(secret.getSecretEnc());
        if (!codeVerifier.isValidCode(rawSecret, totpCode)) {
            throw new InvalidTotpCodeException();
        }

        secret.enable();
        secrets.save(secret);
        return issueBackupCodes(userId);
    }

    @Override
    @Transactional
    public AuthenticatedSession verifyTotp(
            String firstFactorToken, String totpCode, String userAgent, String ipAddress) {
        UUID userId = firstFactorCache.consume(firstFactorToken)
                .orElseThrow(InvalidFirstFactorTokenException::new);
        TotpSecret secret = secrets.findByUserId(userId)
                .filter(TotpSecret::isEnabled)
                .orElseThrow(InvalidTotpCodeException::new);
        if (!codeVerifier.isValidCode(encryptor.decrypt(secret.getSecretEnc()), totpCode)) {
            throw new InvalidTotpCodeException();
        }
        return buildSession(userId, userAgent, ipAddress);
    }

    @Override
    @Transactional
    public AuthenticatedSession verifyBackupCode(
            String firstFactorToken, String rawBackupCode, String userAgent, String ipAddress) {
        UUID userId = firstFactorCache.consume(firstFactorToken)
                .orElseThrow(InvalidFirstFactorTokenException::new);
        String normalized = normalizeBackupCode(rawBackupCode);
        BackupCode matching = backupCodes.findAllByUserId(userId).stream()
                .filter(c -> !c.isUsed())
                .filter(c -> hasher.verify(normalized.toCharArray(), c.getCodeHash()))
                .findFirst()
                .orElseThrow(InvalidTotpCodeException::new);
        matching.markUsed();
        backupCodes.save(matching);
        return buildSession(userId, userAgent, ipAddress);
    }

    @Override
    public BackupCodeStatus getBackupCodeStatus(UUID userId) {
        long total = backupCodes.countByUserId(userId);
        long remaining = backupCodes.countByUserIdAndUsedAtIsNull(userId);
        return new BackupCodeStatus(remaining, total);
    }

    @Override
    @Transactional
    public List<String> regenerateBackupCodes(UUID userId, String totpCode) {
        TotpSecret secret = secrets.findByUserId(userId)
                .filter(TotpSecret::isEnabled)
                .orElseThrow(TwoFactorNotEnabledException::new);
        if (!codeVerifier.isValidCode(encryptor.decrypt(secret.getSecretEnc()), totpCode)) {
            throw new InvalidTotpCodeException();
        }
        backupCodes.deleteAllByUserId(userId);
        return issueBackupCodes(userId);
    }

    @Override
    @Transactional
    public void disable(UUID userId, String totpCode) {
        TotpSecret secret = secrets.findByUserId(userId)
                .filter(TotpSecret::isEnabled)
                .orElseThrow(TwoFactorNotEnabledException::new);
        if (!codeVerifier.isValidCode(encryptor.decrypt(secret.getSecretEnc()), totpCode)) {
            throw new InvalidTotpCodeException();
        }
        secrets.deleteByUserId(userId);
        backupCodes.deleteAllByUserId(userId);
    }

    /** Called by AuthServiceImpl to get a firstFactorToken when password passes. */
    public String issueFirstFactorToken(UUID userId) {
        return firstFactorCache.store(userId, tokenTtl);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<String> issueBackupCodes(UUID userId) {
        List<String> raw = new ArrayList<>(BACKUP_CODE_COUNT);
        for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
            String code = generateCode();
            raw.add(formatCode(code));
            backupCodes.save(new BackupCode(userId, hasher.hash(code.toCharArray())));
        }
        return raw;
    }

    private static String generateCode() {
        char[] code = new char[BACKUP_CODE_LENGTH];
        for (int i = 0; i < BACKUP_CODE_LENGTH; i++) {
            code[i] = CODE_CHARS.charAt(RNG.nextInt(CODE_CHARS.length()));
        }
        return new String(code);
    }

    private static String formatCode(String raw) {
        return raw.substring(0, 3) + "-" + raw.substring(3, 6) + "-" + raw.substring(6);
    }

    private static String normalizeBackupCode(String input) {
        return input == null ? "" : input.replace("-", "").toUpperCase();
    }

    private AuthenticatedSession buildSession(UUID userId, String userAgent, String ipAddress) {
        User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
        user.markLoggedIn();
        users.save(user);
        IssueResult refresh = refreshTokens.issueForNewSession(userId, userAgent, ipAddress);
        return new AuthenticatedSession(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                user.getCreatedAt(),
                jwt.issueAccessToken(userId, user.getEmail()),
                refresh.rawToken(),
                jwt.accessTokenTtlSeconds());
    }
}
