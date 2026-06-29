package app.vaultdoc.core.auth.passkey;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.vaultdoc.core.auth.config.PasskeyProperties;
import app.vaultdoc.core.auth.domain.AccountStatus;
import app.vaultdoc.core.auth.domain.User;
import app.vaultdoc.core.auth.domain.UserRepository;
import app.vaultdoc.core.auth.domain.WebauthnCredential;
import app.vaultdoc.core.auth.domain.WebauthnCredentialRepository;
import app.vaultdoc.core.auth.service.AuthenticatedSession;
import app.vaultdoc.core.auth.service.InvalidCredentialsException;
import app.vaultdoc.core.auth.token.JwtIssuer;
import app.vaultdoc.core.auth.token.RefreshTokenService;
import app.vaultdoc.core.auth.token.RefreshTokenService.IssueResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for orchestration concerns: challenge storage, user lookup,
 * credential persistence, token issuance, deletion authorization. The actual
 * WebAuthn crypto layer is covered by webauthn4j's own test suite, not here.
 *
 * <p>We use a real WebAuthnManager in beginRegistration/beginSignin to
 * confirm the options JSON shape we build is well-formed, but mock it out
 * for the finish flows where we'd otherwise need to forge attestations.
 */
class PasskeyServiceImplTest {

    private WebauthnCredentialRepository credentials;
    private UserRepository users;
    private ChallengeCache challenges;
    private WebAuthnManager webAuthnManager;
    private JwtIssuer jwt;
    private RefreshTokenService refreshTokens;
    private PasskeyServiceImpl service;

    @BeforeEach
    void setUp() {
        credentials = mock(WebauthnCredentialRepository.class);
        users = mock(UserRepository.class);
        challenges = mock(ChallengeCache.class);
        webAuthnManager = mock(WebAuthnManager.class);
        jwt = mock(JwtIssuer.class);
        refreshTokens = mock(RefreshTokenService.class);

        ObjectConverter objectConverter = new ObjectConverter();
        RelyingPartyConfig rp = new RelyingPartyConfig(
                "localhost", "VaultDoc", List.of("http://localhost:4200"));

        service = new PasskeyServiceImpl(
                credentials, users, challenges,
                webAuthnManager, objectConverter,
                rp, jwt, refreshTokens,
                Duration.ofMinutes(5));

        lenient().when(jwt.issueAccessToken(any(UUID.class), anyString())).thenReturn("access-token");
        lenient().when(jwt.accessTokenTtlSeconds()).thenReturn(3600);
    }

    @Test
    void begin_registration_stores_challenge_and_returns_well_formed_options() {
        User user = new User(UUID.randomUUID(), "k@example.com", "Kshitij", "hash");
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(credentials.findAllByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        UUID storedChallengeId = UUID.randomUUID();
        when(challenges.store(eq(ChallengeCache.Flow.REGISTRATION), any(byte[].class), eq(user.getId()), any()))
                .thenReturn(storedChallengeId);

        PasskeyRegistrationStarted started = service.beginRegistration(user.getId());

        assertEquals(storedChallengeId, started.challengeId());
        JsonNode opts = started.optionsJson();
        assertEquals("localhost", opts.path("rp").path("id").asString());
        assertEquals("VaultDoc", opts.path("rp").path("name").asString());
        assertEquals("k@example.com", opts.path("user").path("name").asString());
        assertTrue(opts.path("challenge").isString());
        assertEquals("none", opts.path("attestation").asString());
        assertTrue(opts.path("pubKeyCredParams").isArray());
        assertEquals(2, opts.path("pubKeyCredParams").size());
    }

    @Test
    void begin_registration_lists_existing_credentials_in_excludeCredentials() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "k@example.com", null, "hash");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(credentials.findAllByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                stubCredential(userId, "cred-A".getBytes()),
                stubCredential(userId, "cred-B".getBytes())));
        when(challenges.store(any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        PasskeyRegistrationStarted started = service.beginRegistration(userId);
        assertEquals(2, started.optionsJson().path("excludeCredentials").size());
    }

    @Test
    void finish_registration_rejects_expired_challenge() {
        UUID userId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(challenges.consume(ChallengeCache.Flow.REGISTRATION, challengeId)).thenReturn(Optional.empty());

        assertThrows(PasskeyChallengeExpiredException.class, () ->
                service.finishRegistration(userId, challengeId, "MacBook", emptyJson()));
    }

    @Test
    void finish_registration_rejects_challenge_belonging_to_another_user() {
        UUID userId = UUID.randomUUID();
        UUID challengeOwner = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        when(challenges.consume(ChallengeCache.Flow.REGISTRATION, challengeId))
                .thenReturn(Optional.of(new ChallengeCache.StoredChallenge(new byte[32], challengeOwner)));

        assertThrows(InvalidPasskeyCredentialException.class, () ->
                service.finishRegistration(userId, challengeId, "MacBook", emptyJson()));
    }

    @Test
    void begin_signin_stores_challenge_with_no_user_and_returns_options() {
        UUID storedChallengeId = UUID.randomUUID();
        when(challenges.store(eq(ChallengeCache.Flow.SIGNIN), any(byte[].class), eq(null), any()))
                .thenReturn(storedChallengeId);

        PasskeySigninStarted started = service.beginSignin();
        assertEquals(storedChallengeId, started.challengeId());
        JsonNode opts = started.optionsJson();
        assertEquals("localhost", opts.path("rpId").asString());
        assertTrue(opts.path("challenge").isString());
        assertTrue(opts.path("allowCredentials").isArray());
        assertEquals(0, opts.path("allowCredentials").size());
    }

    @Test
    void finish_signin_rejects_expired_challenge() {
        UUID challengeId = UUID.randomUUID();
        when(challenges.consume(ChallengeCache.Flow.SIGNIN, challengeId)).thenReturn(Optional.empty());

        assertThrows(PasskeyChallengeExpiredException.class, () ->
                service.finishSignin(challengeId, emptyJson(), null, null));
    }

    @Test
    void list_returns_summaries_in_repo_order() {
        UUID userId = UUID.randomUUID();
        when(credentials.findAllByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                stubCredential(userId, "a".getBytes()),
                stubCredential(userId, "b".getBytes())));

        List<PasskeySummary> result = service.list(userId);
        assertEquals(2, result.size());
        assertNotNull(result.get(0).id());
    }

    @Test
    void delete_throws_when_passkey_does_not_belong_to_user() {
        UUID userId = UUID.randomUUID();
        UUID passkeyId = UUID.randomUUID();
        when(credentials.findByIdAndUserId(passkeyId, userId)).thenReturn(Optional.empty());

        assertThrows(PasskeyNotFoundException.class, () -> service.delete(userId, passkeyId));
        verify(credentials, never()).delete(any(WebauthnCredential.class));
    }

    @Test
    void delete_removes_when_owned_by_user() {
        UUID userId = UUID.randomUUID();
        UUID passkeyId = UUID.randomUUID();
        WebauthnCredential cred = stubCredential(userId, "x".getBytes());
        when(credentials.findByIdAndUserId(passkeyId, userId)).thenReturn(Optional.of(cred));

        service.delete(userId, passkeyId);
        verify(credentials, times(1)).delete(cred);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static WebauthnCredential stubCredential(UUID userId, byte[] credentialId) {
        return new WebauthnCredential(
                userId,
                credentialId,
                new byte[]{0x01, 0x02},
                0L,
                UUID.randomUUID(),
                new String[]{"internal"},
                "none",
                false,
                false,
                uuidBytes(userId),
                "Test passkey");
    }

    private static byte[] uuidBytes(UUID u) {
        ByteBuffer b = ByteBuffer.allocate(16);
        b.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());
        return b.array();
    }

    private static JsonNode emptyJson() {
        ObjectNode n = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        n.putObject("response");
        return n;
    }
}
