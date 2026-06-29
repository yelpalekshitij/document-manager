package app.vaultdoc.core.auth.config;

import app.vaultdoc.core.auth.domain.BackupCodeRepository;
import app.vaultdoc.core.auth.domain.PasswordResetTokenRepository;
import app.vaultdoc.core.auth.domain.RefreshTokenRepository;
import app.vaultdoc.core.auth.domain.TotpSecretRepository;
import app.vaultdoc.core.auth.domain.UserRepository;
import app.vaultdoc.core.auth.domain.VerificationTokenRepository;
import app.vaultdoc.core.auth.domain.WebauthnCredentialRepository;
import app.vaultdoc.core.auth.mail.EmailSender;
import app.vaultdoc.core.auth.mail.SpringMailEmailSender;
import app.vaultdoc.core.auth.passkey.ChallengeCache;
import app.vaultdoc.core.auth.passkey.PasskeyService;
import app.vaultdoc.core.auth.passkey.PasskeyServiceImpl;
import app.vaultdoc.core.auth.passkey.RedisChallengeCache;
import app.vaultdoc.core.auth.passkey.RelyingPartyConfig;
import app.vaultdoc.core.auth.password.Argon2idPasswordHasher;
import app.vaultdoc.core.auth.password.PasswordHasher;
import app.vaultdoc.core.auth.service.AuthService;
import app.vaultdoc.core.auth.service.AuthServiceImpl;
import app.vaultdoc.core.auth.service.PasswordResetService;
import app.vaultdoc.core.auth.service.PasswordResetServiceImpl;
import app.vaultdoc.core.auth.service.VerificationService;
import app.vaultdoc.core.auth.token.JwtIssuer;
import app.vaultdoc.core.auth.token.JwtIssuerImpl;
import app.vaultdoc.core.auth.token.RefreshTokenService;
import app.vaultdoc.core.auth.twofa.FirstFactorCache;
import app.vaultdoc.core.auth.twofa.RedisFirstFactorCache;
import app.vaultdoc.core.auth.twofa.TotpSecretEncryptor;
import app.vaultdoc.core.auth.twofa.TwoFactorService;
import app.vaultdoc.core.auth.twofa.TwoFactorServiceImpl;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
@EnableConfigurationProperties({
        AuthProperties.class,
        VerificationProperties.class,
        PasskeyProperties.class,
        TwoFactorProperties.class
})
public class AuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PasswordHasher passwordHasher() {
        return new Argon2idPasswordHasher();
    }

    @Bean
    @ConditionalOnMissingBean
    JwtIssuer jwtIssuer(AuthProperties properties) {
        return new JwtIssuerImpl(properties.jwtSecret(), properties.accessTokenTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    RefreshTokenService refreshTokenService(
            RefreshTokenRepository repository, AuthProperties properties) {
        return new RefreshTokenService(repository, properties.refreshTokenTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    EmailSender emailSender(JavaMailSender mailSender, VerificationProperties properties) {
        return new SpringMailEmailSender(mailSender, properties.fromAddress());
    }

    @Bean
    @ConditionalOnMissingBean
    VerificationService verificationService(
            VerificationTokenRepository tokens,
            UserRepository users,
            EmailSender mail,
            VerificationProperties properties) {
        return new VerificationService(tokens, users, mail, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    PasswordResetService passwordResetService(
            PasswordResetTokenRepository resetTokens,
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordHasher hasher,
            EmailSender mail,
            VerificationProperties properties) {
        return new PasswordResetServiceImpl(resetTokens, users, refreshTokens, hasher, mail, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    AuthService authService(
            UserRepository users,
            PasswordHasher hasher,
            JwtIssuer jwt,
            RefreshTokenService refreshTokens,
            VerificationService verification) {
        return new AuthServiceImpl(users, hasher, jwt, refreshTokens, verification);
    }

    // ── 2FA ─────────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    TotpSecretEncryptor totpSecretEncryptor(TwoFactorProperties properties) {
        return new TotpSecretEncryptor(properties.encryptionKey());
    }

    @Bean
    @ConditionalOnMissingBean
    FirstFactorCache firstFactorCache(StringRedisTemplate redis) {
        return new RedisFirstFactorCache(redis);
    }

    @Bean
    @ConditionalOnMissingBean
    TwoFactorService twoFactorService(
            TotpSecretRepository secrets,
            BackupCodeRepository backupCodes,
            UserRepository users,
            PasswordHasher hasher,
            JwtIssuer jwt,
            RefreshTokenService refreshTokens,
            TotpSecretEncryptor encryptor,
            FirstFactorCache firstFactorCache,
            TwoFactorProperties properties) {
        return new TwoFactorServiceImpl(
                secrets, backupCodes, users, hasher, jwt, refreshTokens,
                encryptor, firstFactorCache,
                properties.tokenTtl(), properties.issuer());
    }

    // ── Passkeys ────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    RelyingPartyConfig relyingPartyConfig(PasskeyProperties properties) {
        return new RelyingPartyConfig(properties.rpId(), properties.rpName(), properties.origins());
    }

    @Bean
    @ConditionalOnMissingBean
    ObjectConverter webauthnObjectConverter() {
        return new ObjectConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    WebAuthnManager webAuthnManager(ObjectConverter objectConverter) {
        return WebAuthnManager.createNonStrictWebAuthnManager(objectConverter);
    }

    @Bean
    @ConditionalOnMissingBean
    ChallengeCache passkeyChallengeCache(StringRedisTemplate redis) {
        return new RedisChallengeCache(redis);
    }

    @Bean
    @ConditionalOnMissingBean
    PasskeyService passkeyService(
            WebauthnCredentialRepository credentials,
            UserRepository users,
            ChallengeCache challenges,
            WebAuthnManager webAuthnManager,
            ObjectConverter objectConverter,
            RelyingPartyConfig rp,
            JwtIssuer jwt,
            RefreshTokenService refreshTokens,
            PasskeyProperties properties) {
        return new PasskeyServiceImpl(
                credentials, users, challenges,
                webAuthnManager, objectConverter,
                rp, jwt, refreshTokens,
                properties.challengeTtl());
    }
}
