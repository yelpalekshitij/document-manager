package app.vaultdoc.core.auth.service;

import java.time.Instant;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private UserRepository users;
    private PasswordHasher hasher;
    private JwtIssuer jwt;
    private RefreshTokenService refreshTokens;
    private VerificationService verification;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        hasher = mock(PasswordHasher.class);
        jwt = mock(JwtIssuer.class);
        refreshTokens = mock(RefreshTokenService.class);
        verification = mock(VerificationService.class);
        service = new AuthServiceImpl(users, hasher, jwt, refreshTokens, verification);

        when(jwt.issueAccessToken(any(UUID.class), anyString())).thenReturn("access-token");
        when(jwt.accessTokenTtlSeconds()).thenReturn(3600);
    }

    @Test
    void register_creates_pending_user_and_does_not_issue_tokens() {
        when(users.existsByEmailIgnoreCase("kshitij@example.com")).thenReturn(false);
        when(hasher.hash(any(char[].class))).thenReturn("hashed");
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        when(users.save(savedUser.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        RegistrationResult result = service.register(
                new RegisterCommand("KSHITIJ@example.com", "secret-pw-12345".toCharArray(), "Kshitij"));

        assertEquals("kshitij@example.com", savedUser.getValue().getEmail());
        assertEquals(AccountStatus.PENDING_VERIFICATION, savedUser.getValue().getStatus());
        assertTrue(result.verificationRequired());
        verify(verification, times(1)).issueOnRegistration(any(User.class));
        verify(refreshTokens, times(0)).issueForNewSession(any(), any(), any());
    }

    @Test
    void register_rejects_duplicate_email() {
        when(users.existsByEmailIgnoreCase("dup@example.com")).thenReturn(true);
        assertThrows(DuplicateEmailException.class, () ->
                service.register(new RegisterCommand("dup@example.com", "secret-pw-12345".toCharArray(), null)));
    }

    @Test
    void login_throws_unverified_for_pending_account() {
        User user = pendingUser("pending@example.com");
        when(users.findByEmailIgnoreCase("pending@example.com")).thenReturn(Optional.of(user));
        when(hasher.verify(any(char[].class), eq("stored-hash"))).thenReturn(true);
        user.setPasswordHash("stored-hash");

        assertThrows(UnverifiedAccountException.class, () ->
                service.login(new LoginCommand("pending@example.com", "pw".toCharArray(), null, null)));
    }

    @Test
    void login_succeeds_for_active_account() {
        User user = activeUser("k@example.com");
        when(users.findByEmailIgnoreCase("k@example.com")).thenReturn(Optional.of(user));
        when(hasher.verify(any(char[].class), eq("stored-hash"))).thenReturn(true);
        when(refreshTokens.issueForNewSession(eq(user.getId()), any(), any()))
                .thenReturn(new IssueResult(user.getId(), UUID.randomUUID(), "refresh", Instant.now()));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        AuthenticatedSession session = service.login(
                new LoginCommand("k@example.com", "pw".toCharArray(), "agent", "127.0.0.1"));

        assertEquals("access-token", session.accessToken());
        verify(refreshTokens).issueForNewSession(user.getId(), "agent", "127.0.0.1");
    }

    @Test
    void login_rejects_unknown_email() {
        when(users.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());
        assertThrows(InvalidCredentialsException.class, () ->
                service.login(new LoginCommand("ghost@example.com", "pw".toCharArray(), null, null)));
    }

    @Test
    void login_rejects_wrong_password() {
        User user = activeUser("k@example.com");
        when(users.findByEmailIgnoreCase("k@example.com")).thenReturn(Optional.of(user));
        when(hasher.verify(any(char[].class), eq("stored-hash"))).thenReturn(false);

        assertThrows(InvalidCredentialsException.class, () ->
                service.login(new LoginCommand("k@example.com", "wrong".toCharArray(), null, null)));
    }

    @Test
    void login_rejects_locked_account() {
        User user = activeUser("k@example.com");
        user.setStatus(AccountStatus.LOCKED);
        when(users.findByEmailIgnoreCase("k@example.com")).thenReturn(Optional.of(user));

        assertThrows(AccountLockedException.class, () ->
                service.login(new LoginCommand("k@example.com", "pw".toCharArray(), null, null)));
    }

    @Test
    void verify_email_consumes_token_and_issues_session() {
        User user = pendingUser("k@example.com");
        user.setStatus(AccountStatus.ACTIVE); // post-consume state
        when(verification.consume("token")).thenReturn(user);
        when(refreshTokens.issueForNewSession(eq(user.getId()), any(), any()))
                .thenReturn(new IssueResult(user.getId(), UUID.randomUUID(), "refresh", Instant.now()));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        AuthenticatedSession session = service.verifyEmail("token", null, null);

        assertEquals("access-token", session.accessToken());
        assertEquals(user.getId(), session.userId());
    }

    @Test
    void refresh_rotates_and_returns_new_session() {
        UUID userId = UUID.randomUUID();
        User user = activeUser("k@example.com");
        when(refreshTokens.rotate(eq("old"), any(), any()))
                .thenReturn(new IssueResult(user.getId(), UUID.randomUUID(), "new-refresh", Instant.now()));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        AuthenticatedSession session = service.refresh("old", null, null);

        assertEquals("new-refresh", session.refreshToken());
    }

    @Test
    void login_triggers_2fa_gate_when_enabled() {
        User user = activeUser("k@example.com");
        when(users.findByEmailIgnoreCase("k@example.com")).thenReturn(Optional.of(user));
        when(hasher.verify(any(char[].class), eq("stored-hash"))).thenReturn(true);

        TwoFactorService twoFa = mock(TwoFactorService.class);
        when(twoFa.isEnabled(user.getId())).thenReturn(true);
        when(twoFa.issueFirstFactorToken(user.getId())).thenReturn("ff-token");
        service.setTwoFactorService(twoFa);

        TwoFactorRequiredException ex = assertThrows(TwoFactorRequiredException.class, () ->
                service.login(new LoginCommand("k@example.com", "pw".toCharArray(), null, null)));
        assertEquals("ff-token", ex.getFirstFactorToken());
    }

    @Test
    void login_issues_session_when_2fa_not_enabled() {
        User user = activeUser("k@example.com");
        when(users.findByEmailIgnoreCase("k@example.com")).thenReturn(Optional.of(user));
        when(hasher.verify(any(char[].class), eq("stored-hash"))).thenReturn(true);
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        TwoFactorService twoFa = mock(TwoFactorService.class);
        when(twoFa.isEnabled(user.getId())).thenReturn(false);
        service.setTwoFactorService(twoFa);

        when(refreshTokens.issueForNewSession(eq(user.getId()), any(), any()))
                .thenReturn(new IssueResult(user.getId(), UUID.randomUUID(), "refresh", Instant.now()));

        AuthenticatedSession session = service.login(
                new LoginCommand("k@example.com", "pw".toCharArray(), null, null));
        assertEquals("access-token", session.accessToken());
    }

    @Test
    void resend_verification_delegates_to_service() {
        service.resendVerification("k@example.com");
        verify(verification, times(1)).resend("k@example.com");
    }

    private static User activeUser(String email) {
        User u = new User(UUID.randomUUID(), email, null, "stored-hash");
        u.setStatus(AccountStatus.ACTIVE);
        return u;
    }

    private static User pendingUser(String email) {
        User u = new User(UUID.randomUUID(), email, null, "stored-hash");
        u.setStatus(AccountStatus.PENDING_VERIFICATION);
        return u;
    }
}
