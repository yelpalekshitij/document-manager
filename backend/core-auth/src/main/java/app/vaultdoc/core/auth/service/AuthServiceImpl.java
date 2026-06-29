package app.vaultdoc.core.auth.service;

import java.util.Optional;
import java.util.UUID;

import app.vaultdoc.core.auth.domain.AccountStatus;
import app.vaultdoc.core.auth.domain.User;
import app.vaultdoc.core.auth.domain.UserRepository;
import app.vaultdoc.core.auth.password.PasswordHasher;
import app.vaultdoc.core.auth.token.JwtIssuer;
import app.vaultdoc.core.auth.token.RefreshTokenService;
import app.vaultdoc.core.auth.token.RefreshTokenService.IssueResult;
import app.vaultdoc.core.auth.twofa.TwoFactorRequiredException;
import app.vaultdoc.core.auth.twofa.TwoFactorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

public class AuthServiceImpl implements AuthService {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final JwtIssuer jwt;
    private final RefreshTokenService refreshTokens;
    private final VerificationService verification;
    // nullable — null means 2FA feature is not wired (test contexts)
    private TwoFactorService twoFactorService;

    /** Optional setter — Spring auto-injects TwoFactorService when available. */
    @Autowired(required = false)
    public void setTwoFactorService(TwoFactorService twoFactorService) {
        this.twoFactorService = twoFactorService;
    }

    public AuthServiceImpl(
            UserRepository users,
            PasswordHasher hasher,
            JwtIssuer jwt,
            RefreshTokenService refreshTokens,
            VerificationService verification) {
        this.users = users;
        this.hasher = hasher;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.verification = verification;
    }

    @Override
    @Transactional
    public RegistrationResult register(RegisterCommand command) {
        String email = normalizeEmail(command.email());
        if (users.existsByEmailIgnoreCase(email)) {
            throw new DuplicateEmailException(email);
        }
        String hash = hasher.hash(command.password().clone());
        User user = new User(UUID.randomUUID(), email, command.displayName(), hash);
        user.setStatus(AccountStatus.PENDING_VERIFICATION);
        users.save(user);
        verification.issueOnRegistration(user);
        return RegistrationResult.from(user, true);
    }

    @Override
    @Transactional
    public AuthenticatedSession login(LoginCommand command) {
        String email = normalizeEmail(command.email());
        User user = users.findByEmailIgnoreCase(email)
                .orElseThrow(InvalidCredentialsException::new);

        if (user.getStatus() == AccountStatus.LOCKED) {
            throw new AccountLockedException();
        }

        String storedHash = user.getPasswordHash();
        if (storedHash == null || !hasher.verify(command.password().clone(), storedHash)) {
            throw new InvalidCredentialsException();
        }

        if (user.getStatus() == AccountStatus.PENDING_VERIFICATION) {
            throw new UnverifiedAccountException();
        }

        // Check 2FA after all other gates pass.
        if (twoFactorService != null && twoFactorService.isEnabled(user.getId())) {
            String firstFactorToken = twoFactorService.issueFirstFactorToken(user.getId());
            throw new TwoFactorRequiredException(firstFactorToken);
        }

        user.markLoggedIn();
        users.save(user);
        IssueResult refresh = refreshTokens.issueForNewSession(
                user.getId(), command.userAgent(), command.ipAddress());
        return buildSession(user, refresh);
    }

    @Override
    @Transactional
    public AuthenticatedSession refresh(String refreshToken, String userAgent, String ipAddress) {
        IssueResult rotated = refreshTokens.rotate(refreshToken, userAgent, ipAddress);
        User user = users.findById(rotated.userId()).orElseThrow(InvalidCredentialsException::new);
        return buildSession(user, rotated);
    }

    @Override
    @Transactional
    public AuthenticatedSession verifyEmail(String rawToken, String userAgent, String ipAddress) {
        User user = verification.consume(rawToken);
        user.markLoggedIn();
        users.save(user);
        IssueResult refresh = refreshTokens.issueForNewSession(user.getId(), userAgent, ipAddress);
        return buildSession(user, refresh);
    }

    @Override
    public void resendVerification(String email) {
        verification.resend(email);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    @Override
    public Optional<User> findById(UUID userId) {
        return users.findById(userId);
    }

    private AuthenticatedSession buildSession(User user, IssueResult refresh) {
        String access = jwt.issueAccessToken(user.getId(), user.getEmail());
        return new AuthenticatedSession(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                user.getCreatedAt(),
                access,
                refresh.rawToken(),
                jwt.accessTokenTtlSeconds());
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            throw new InvalidCredentialsException();
        }
        return email.trim().toLowerCase();
    }
}
