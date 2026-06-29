package app.vaultdoc.core.auth.passkey;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.util.exception.WebAuthnException;
import org.springframework.transaction.annotation.Transactional;

public class PasskeyServiceImpl implements PasskeyService {

    private static final int CHALLENGE_BYTES = 32;
    private static final long TIMEOUT_MS = 120_000L;
    private static final SecureRandom RNG = new SecureRandom();

    private final WebauthnCredentialRepository credentials;
    private final UserRepository users;
    private final ChallengeCache challenges;
    private final WebAuthnManager webAuthnManager;
    private final ObjectConverter objectConverter;
    private final JsonMapper jackson;
    private final AttestedCredentialDataConverter attestedConverter;
    private final RelyingPartyConfig rp;
    private final JwtIssuer jwt;
    private final RefreshTokenService refreshTokens;
    private final java.time.Duration challengeTtl;

    public PasskeyServiceImpl(
            WebauthnCredentialRepository credentials,
            UserRepository users,
            ChallengeCache challenges,
            WebAuthnManager webAuthnManager,
            ObjectConverter objectConverter,
            RelyingPartyConfig rp,
            JwtIssuer jwt,
            RefreshTokenService refreshTokens,
            java.time.Duration challengeTtl) {
        this.credentials = credentials;
        this.users = users;
        this.challenges = challenges;
        this.webAuthnManager = webAuthnManager;
        this.objectConverter = objectConverter;
        this.jackson = JsonMapper.builder().build();
        this.attestedConverter = new AttestedCredentialDataConverter(objectConverter);
        this.rp = rp;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.challengeTtl = challengeTtl;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Registration
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PasskeyRegistrationStarted beginRegistration(UUID userId) {
        User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
        byte[] challenge = randomBytes(CHALLENGE_BYTES);
        UUID challengeId = challenges.store(ChallengeCache.Flow.REGISTRATION, challenge, user.getId(), challengeTtl);

        ObjectNode options = jackson.createObjectNode();

        ObjectNode rpNode = options.putObject("rp");
        rpNode.put("name", rp.rpName());
        rpNode.put("id", rp.rpId());

        ObjectNode userNode = options.putObject("user");
        userNode.put("id", encodeBase64Url(uuidToBytes(user.getId())));
        userNode.put("name", user.getEmail());
        userNode.put("displayName", Optional.ofNullable(user.getDisplayName()).orElse(user.getEmail()));

        options.put("challenge", encodeBase64Url(challenge));
        options.put("timeout", TIMEOUT_MS);
        options.put("attestation", "none");

        ArrayNode pubKeyCredParams = options.putArray("pubKeyCredParams");
        addAlg(pubKeyCredParams, -7);   // ES256
        addAlg(pubKeyCredParams, -257); // RS256

        ObjectNode authenticatorSelection = options.putObject("authenticatorSelection");
        authenticatorSelection.put("residentKey", "preferred");
        authenticatorSelection.put("userVerification", "preferred");

        ArrayNode excludeCreds = options.putArray("excludeCredentials");
        for (WebauthnCredential existing : credentials.findAllByUserIdOrderByCreatedAtDesc(user.getId())) {
            ObjectNode cred = excludeCreds.addObject();
            cred.put("type", "public-key");
            cred.put("id", encodeBase64Url(existing.getCredentialId()));
        }

        return new PasskeyRegistrationStarted(challengeId, options);
    }

    @Override
    @Transactional
    public PasskeySummary finishRegistration(UUID userId, UUID challengeId, String label, JsonNode credentialJson) {
        ChallengeCache.StoredChallenge stored = challenges
                .consume(ChallengeCache.Flow.REGISTRATION, challengeId)
                .orElseThrow(PasskeyChallengeExpiredException::new);

        if (stored.userId() == null || !stored.userId().equals(userId)) {
            throw new InvalidPasskeyCredentialException("Challenge does not belong to the calling user");
        }

        byte[] attestationObject = readBase64UrlField(credentialJson, "response", "attestationObject");
        byte[] clientDataJSON = readBase64UrlField(credentialJson, "response", "clientDataJSON");
        Set<AuthenticatorTransport> transports = readTransports(credentialJson);
        Set<String> transportStrings2 = transports.stream()
                .map(AuthenticatorTransport::getValue)
                .collect(java.util.stream.Collectors.toSet());

        RegistrationRequest request = new RegistrationRequest(attestationObject, clientDataJSON, transportStrings2);
        RegistrationParameters params = new RegistrationParameters(
                buildServerProperty(stored.challenge()),
                List.of(
                        new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
                        new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256)),
                /* userVerificationRequired */ false,
                /* userPresenceRequired */ true);

        RegistrationData data;
        try {
            data = webAuthnManager.parse(request);
            webAuthnManager.validate(data, params);
        } catch (WebAuthnException e) {
            throw new InvalidPasskeyCredentialException("Attestation verification failed: " + e.getMessage(), e);
        }

        AttestedCredentialData attestedCredentialData =
                data.getAttestationObject().getAuthenticatorData().getAttestedCredentialData();
        byte[] attestationDataBytes = attestedConverter.convert(attestedCredentialData);

        long signCount = data.getAttestationObject().getAuthenticatorData().getSignCount();
        UUID aaguid = attestedCredentialData.getAaguid().getValue();
        boolean backupEligible = data.getAttestationObject().getAuthenticatorData().isFlagBE();
        boolean backupState = data.getAttestationObject().getAuthenticatorData().isFlagBS();
        String attestationFormat = data.getAttestationObject().getFormat();
        String[] transportStrings = transports.stream().map(AuthenticatorTransport::getValue).toArray(String[]::new);

        String finalLabel = (label == null || label.isBlank())
                ? defaultLabel(attestationFormat, transports)
                : label.trim();

        WebauthnCredential credential = new WebauthnCredential(
                userId,
                attestedCredentialData.getCredentialId(),
                attestationDataBytes,
                signCount,
                aaguid,
                transportStrings,
                attestationFormat,
                backupEligible,
                backupState,
                uuidToBytes(userId),
                finalLabel);
        credentials.save(credential);
        return PasskeySummary.from(credential);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sign-in
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PasskeySigninStarted beginSignin() {
        byte[] challenge = randomBytes(CHALLENGE_BYTES);
        // No user context yet — discoverable credential will tell us who.
        UUID challengeId = challenges.store(ChallengeCache.Flow.SIGNIN, challenge, null, challengeTtl);

        ObjectNode options = jackson.createObjectNode();
        options.put("challenge", encodeBase64Url(challenge));
        options.put("timeout", TIMEOUT_MS);
        options.put("rpId", rp.rpId());
        options.put("userVerification", "preferred");
        options.putArray("allowCredentials"); // empty → discoverable
        return new PasskeySigninStarted(challengeId, options);
    }

    @Override
    @Transactional
    public AuthenticatedSession finishSignin(
            UUID challengeId, JsonNode assertionJson, String userAgent, String ipAddress) {
        ChallengeCache.StoredChallenge stored = challenges
                .consume(ChallengeCache.Flow.SIGNIN, challengeId)
                .orElseThrow(PasskeyChallengeExpiredException::new);

        byte[] credentialId = readBase64UrlField(assertionJson, "rawId");
        byte[] authenticatorData = readBase64UrlField(assertionJson, "response", "authenticatorData");
        byte[] clientDataJSON = readBase64UrlField(assertionJson, "response", "clientDataJSON");
        byte[] signature = readBase64UrlField(assertionJson, "response", "signature");
        byte[] userHandle = optionalBase64UrlField(assertionJson, "response", "userHandle");

        WebauthnCredential stored2 = credentials.findByCredentialId(credentialId)
                .orElseThrow(() -> new InvalidPasskeyCredentialException("Credential not registered"));

        AttestedCredentialData attestedCredentialData =
                attestedConverter.convert(stored2.getAttestationData());

        Set<AuthenticatorTransport> transports = new HashSet<>();
        for (String t : stored2.getTransports()) {
            try { transports.add(AuthenticatorTransport.create(t)); } catch (Exception ignored) {}
        }

        com.webauthn4j.credential.CredentialRecord credentialRecord =
                new com.webauthn4j.credential.CredentialRecordImpl(
                        /* attestationStatement */ null,
                        /* uvInitialized */ null,
                        /* backupEligible */ stored2.isBackupEligible(),
                        /* backedUp */ stored2.isBackupState(),
                        stored2.getSignCount(),
                        attestedCredentialData,
                        /* authenticatorExtensions */ null,
                        /* clientData */ null,
                        /* clientExtensions */ null,
                        transports);

        com.webauthn4j.data.AuthenticationRequest authRequest = new com.webauthn4j.data.AuthenticationRequest(
                credentialId, userHandle, authenticatorData, clientDataJSON, signature);
        com.webauthn4j.data.AuthenticationParameters authParams = new com.webauthn4j.data.AuthenticationParameters(
                buildServerProperty(stored.challenge()),
                credentialRecord,
                /* allowCredentials */ null,
                /* userVerificationRequired */ false,
                /* userPresenceRequired */ true);

        try {
            com.webauthn4j.data.AuthenticationData authData = webAuthnManager.parse(authRequest);
            webAuthnManager.validate(authData, authParams);
            long newCount = authData.getAuthenticatorData().getSignCount();
            stored2.markUsed(newCount);
            credentials.save(stored2);
        } catch (WebAuthnException e) {
            throw new InvalidPasskeyCredentialException("Assertion verification failed: " + e.getMessage(), e);
        }

        User user = users.findById(stored2.getUserId()).orElseThrow(InvalidCredentialsException::new);
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }
        user.markLoggedIn();
        users.save(user);

        IssueResult refresh = refreshTokens.issueForNewSession(user.getId(), userAgent, ipAddress);
        return new AuthenticatedSession(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                user.getCreatedAt(),
                jwt.issueAccessToken(user.getId(), user.getEmail()),
                refresh.rawToken(),
                jwt.accessTokenTtlSeconds());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // List / delete
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public List<PasskeySummary> list(UUID userId) {
        return credentials.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(PasskeySummary::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID passkeyId) {
        WebauthnCredential cred = credentials.findByIdAndUserId(passkeyId, userId)
                .orElseThrow(PasskeyNotFoundException::new);
        credentials.delete(cred);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private ServerProperty buildServerProperty(byte[] challengeBytes) {
        Set<Origin> origins = new HashSet<>();
        for (String o : rp.origins()) {
            origins.add(new Origin(o));
        }
        Challenge challenge = new DefaultChallenge(challengeBytes);
        return new ServerProperty(origins, rp.rpId(), challenge, /* tokenBindingId */ (byte[]) null);
    }

    private static byte[] readBase64UrlField(JsonNode root, String... path) {
        JsonNode node = root;
        for (String p : path) {
            if (node == null) break;
            node = node.get(p);
        }
        if (node == null || node.isNull() || !node.isString()) {
            throw new InvalidPasskeyCredentialException(
                    "Missing required base64url field: " + String.join(".", path));
        }
        return decodeBase64Url(node.asString());
    }

    private static byte[] optionalBase64UrlField(JsonNode root, String... path) {
        JsonNode node = root;
        for (String p : path) {
            if (node == null) break;
            node = node.get(p);
        }
        if (node == null || node.isNull() || !node.isString()) {
            return null;
        }
        return decodeBase64Url(node.asString());
    }

    private static Set<AuthenticatorTransport> readTransports(JsonNode root) {
        Set<AuthenticatorTransport> result = new HashSet<>();
        JsonNode arr = root.get("transports");
        if (arr == null || !arr.isArray()) return result;
        for (JsonNode v : arr) {
            if (!v.isString()) continue;
            try {
                result.add(AuthenticatorTransport.create(v.asString()));
            } catch (IllegalArgumentException ignored) {
                // Unknown transport — webauthn4j refuses to construct an enum for it. Skip.
            }
        }
        return result;
    }

    private static void addAlg(ArrayNode arr, int alg) {
        ObjectNode entry = arr.addObject();
        entry.put("type", "public-key");
        entry.put("alg", alg);
    }

    private static String defaultLabel(String attestationFormat, Set<AuthenticatorTransport> transports) {
        if (transports.contains(AuthenticatorTransport.INTERNAL)) {
            return "Platform passkey";
        }
        if (transports.contains(AuthenticatorTransport.USB)) {
            return "Security key";
        }
        if (transports.contains(AuthenticatorTransport.HYBRID)) {
            return "Phone";
        }
        return Optional.ofNullable(attestationFormat).orElse("Passkey");
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private static byte[] uuidToBytes(UUID u) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());
        return buf.array();
    }

    private static String encodeBase64Url(byte[] bytes) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decodeBase64Url(String s) {
        // Tolerate padded and unpadded forms.
        String stripped = s.replace("\n", "").replace("\r", "").trim();
        return java.util.Base64.getUrlDecoder().decode(stripped);
    }
}
