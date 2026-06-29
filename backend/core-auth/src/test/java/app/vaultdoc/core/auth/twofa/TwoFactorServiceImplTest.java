package app.vaultdoc.core.auth.twofa;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.vaultdoc.core.auth.domain.BackupCode;
import app.vaultdoc.core.auth.domain.BackupCodeRepository;
import app.vaultdoc.core.auth.domain.TotpSecret;
import app.vaultdoc.core.auth.domain.TotpSecretRepository;
import app.vaultdoc.core.auth.domain.User;
import app.vaultdoc.core.auth.domain.UserRepository;
import app.vaultdoc.core.auth.password.PasswordHasher;
import app.vaultdoc.core.auth.token.JwtIssuer;
import app.vaultdoc.core.auth.token.RefreshTokenService;
import app.vaultdoc.core.auth.token.RefreshTokenService.IssueResult;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TwoFactorServiceImplTest {

    private static final String ENC_KEY = "dev-only-2fa-enc-key-32chars-exact";

    private TotpSecretRepository secretRepo;
    private BackupCodeRepository backupRepo;
    private UserRepository userRepo;
    private PasswordHasher hasher;
    private JwtIssuer jwt;
    private RefreshTokenService refreshTokens;
    private TotpSecretEncryptor encryptor;
    private FakeFirstFactorCache ffCache;
    private TwoFactorServiceImpl service;

    @BeforeEach
    void setUp() {
        secretRepo = mock(TotpSecretRepository.class);
        backupRepo = mock(BackupCodeRepository.class);
        userRepo = mock(UserRepository.class);
        hasher = mock(PasswordHasher.class);
        when(hasher.hash(any(char[].class))).thenAnswer(i -> new String((char[]) i.getArgument(0)) + "_hashed");
        when(hasher.verify(any(char[].class), anyString()))
                .thenAnswer(i -> {
                    char[] plain = i.getArgument(0);
                    String encoded = i.getArgument(1);
                    return encoded.equals(new String(plain) + "_hashed");
                });
        jwt = mock(JwtIssuer.class);
        refreshTokens = mock(RefreshTokenService.class);
        encryptor = new TotpSecretEncryptor(ENC_KEY);
        ffCache = new FakeFirstFactorCache();

        when(jwt.issueAccessToken(any(), anyString())).thenReturn("access-token");
        when(jwt.accessTokenTtlSeconds()).thenReturn(3600);
        when(refreshTokens.issueForNewSession(any(), any(), any()))
                .thenAnswer(i -> new IssueResult(i.getArgument(0), UUID.randomUUID(), "refresh", Instant.now()));
        when(secretRepo.save(any(TotpSecret.class))).thenAnswer(i -> i.getArgument(0));
        when(backupRepo.save(any(BackupCode.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service = new TwoFactorServiceImpl(
                secretRepo, backupRepo, userRepo, hasher, jwt, refreshTokens,
                encryptor, ffCache, Duration.ofMinutes(10), "VaultDoc");
    }

    @Test
    void isEnabled_returns_false_when_no_secret_row() {
        when(secretRepo.findByUserId(any())).thenReturn(Optional.empty());
        assertFalse(service.isEnabled(UUID.randomUUID()));
    }

    @Test
    void isEnabled_returns_true_when_enabled() {
        UUID userId = UUID.randomUUID();
        TotpSecret secret = enabledSecret(userId);
        when(secretRepo.findByUserId(userId)).thenReturn(Optional.of(secret));
        assertTrue(service.isEnabled(userId));
    }

    @Test
    void beginSetup_creates_disabled_secret_and_returns_otpauth_uri() {
        UUID userId = UUID.randomUUID();
        when(secretRepo.findByUserId(userId)).thenReturn(Optional.empty());
        when(userRepo.findById(userId)).thenReturn(Optional.of(activeUser(userId)));

        TwoFactorSetupStarted result = service.beginSetup(userId);

        assertNotNull(result.otpauthUri());
        assertTrue(result.otpauthUri().startsWith("otpauth://totp/"));
        assertTrue(result.otpauthUri().contains("VaultDoc"));
        verify(secretRepo, times(1)).save(any(TotpSecret.class));
    }

    @Test
    void beginSetup_throws_if_already_enabled() {
        UUID userId = UUID.randomUUID();
        when(secretRepo.findByUserId(userId)).thenReturn(Optional.of(enabledSecret(userId)));
        assertThrows(TwoFactorAlreadyEnabledException.class,
                () -> service.beginSetup(userId));
    }

    @Test
    void finishSetup_with_correct_code_enables_and_returns_10_backup_codes() {
        UUID userId = UUID.randomUUID();
        // Use a known secret so we can generate a valid TOTP code
        String rawSecret = "JBSWY3DPEHPK3PXP";
        TotpSecret secret = new TotpSecret(userId, encryptor.encrypt(rawSecret));
        when(secretRepo.findByUserId(userId)).thenReturn(Optional.of(secret));

        String validCode = generateCurrentCode(rawSecret);
        List<String> codes = service.finishSetup(userId, validCode);

        assertEquals(10, codes.size());
        assertTrue(codes.stream().allMatch(c -> c.matches("[A-Z0-9]{3}-[A-Z0-9]{3}-[A-Z0-9]{3}")));
        assertTrue(secret.isEnabled());
    }

    @Test
    void finishSetup_with_wrong_code_throws() {
        UUID userId = UUID.randomUUID();
        TotpSecret secret = new TotpSecret(userId, encryptor.encrypt("JBSWY3DPEHPK3PXP"));
        when(secretRepo.findByUserId(userId)).thenReturn(Optional.of(secret));
        assertThrows(InvalidTotpCodeException.class,
                () -> service.finishSetup(userId, "000000"));
    }

    @Test
    void verifyTotp_with_expired_token_throws() {
        assertThrows(InvalidFirstFactorTokenException.class,
                () -> service.verifyTotp("unknown-token", "123456", null, null));
    }

    @Test
    void verifyTotp_with_wrong_code_throws() {
        UUID userId = UUID.randomUUID();
        String ffToken = ffCache.store(userId, Duration.ofMinutes(10));
        TotpSecret secret = enabledSecret(userId);
        when(secretRepo.findByUserId(userId)).thenReturn(Optional.of(secret));
        assertThrows(InvalidTotpCodeException.class,
                () -> service.verifyTotp(ffToken, "000000", null, null));
    }

    @Test
    void verifyBackupCode_correct_code_issues_session_and_marks_used() {
        UUID userId = UUID.randomUUID();
        String raw = "A3FK92P77";
        String formatted = "A3F-K92-P77";
        String hash = hasher.hash(raw.toCharArray());
        BackupCode code = new BackupCode(userId, hash);

        String ffToken = ffCache.store(userId, Duration.ofMinutes(10));
        when(backupRepo.findAllByUserId(userId)).thenReturn(List.of(code));
        when(userRepo.findById(userId)).thenReturn(Optional.of(activeUser(userId)));

        service.verifyBackupCode(ffToken, formatted, null, null);

        assertTrue(code.isUsed());
        verify(backupRepo, times(1)).save(code);
    }

    @Test
    void verifyBackupCode_wrong_code_throws() {
        UUID userId = UUID.randomUUID();
        String ffToken = ffCache.store(userId, Duration.ofMinutes(10));
        when(backupRepo.findAllByUserId(userId)).thenReturn(List.of());
        assertThrows(InvalidTotpCodeException.class,
                () -> service.verifyBackupCode(ffToken, "XXX-YYY-ZZZ", null, null));
    }

    @Test
    void disable_with_correct_code_deletes_secret_and_backup_codes() {
        UUID userId = UUID.randomUUID();
        String rawSecret = "JBSWY3DPEHPK3PXP";
        TotpSecret secret = enabledSecret(userId);
        when(secretRepo.findByUserId(userId)).thenReturn(Optional.of(secret));

        String validCode = generateCurrentCode(rawSecret);
        service.disable(userId, validCode);

        verify(secretRepo, times(1)).deleteByUserId(userId);
        verify(backupRepo, times(1)).deleteAllByUserId(userId);
    }

    @Test
    void issueFirstFactorToken_returns_non_null_string() {
        String token = service.issueFirstFactorToken(UUID.randomUUID());
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TotpSecret enabledSecret(UUID userId) {
        // Use a deterministic known secret for verify tests
        String rawSecret = "JBSWY3DPEHPK3PXP";
        TotpSecret s = new TotpSecret(userId, encryptor.encrypt(rawSecret));
        s.enable();
        return s;
    }

    private static User activeUser(UUID userId) {
        User u = new User(userId, "k@example.com", null, "hash");
        u.setStatus(app.vaultdoc.core.auth.domain.AccountStatus.ACTIVE);
        return u;
    }

    private static String generateCurrentCode(String secret) {
        try {
            DefaultCodeGenerator gen = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
            long period = System.currentTimeMillis() / 1000 / 30;
            return gen.generate(secret, period);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** In-memory FirstFactorCache for unit tests. */
    private static class FakeFirstFactorCache implements FirstFactorCache {
        private final java.util.Map<String, UUID> store = new java.util.HashMap<>();

        @Override
        public String store(UUID userId, Duration ttl) {
            String token = UUID.randomUUID().toString();
            store.put(token, userId);
            return token;
        }

        @Override
        public Optional<UUID> consume(String rawToken) {
            return Optional.ofNullable(store.remove(rawToken));
        }
    }
}
