package app.vaultdoc.gateway.auth;

import java.util.UUID;

import app.vaultdoc.gateway.support.RecordingEmailSender;
import app.vaultdoc.gateway.support.TestcontainersConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wire-shape integration tests for the passkey endpoints. We do not exercise
 * the full WebAuthn crypto loop here — that would require a software
 * authenticator emulator (webauthn4j-test) plus a JSON ↔ typed-options bridge
 * substantial enough to be its own slice. The orchestration is covered by
 * {@code PasskeyServiceImplTest}; the crypto layer is covered by webauthn4j's
 * own test suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, RecordingEmailSender.AsBean.class})
@TestPropertySource(properties = {
        "vaultdoc.elasticsearch.url=http://localhost:1",
        "vaultdoc.s3.endpoint=http://localhost:1",
        "vaultdoc.s3.access-key=test",
        "vaultdoc.s3.secret-key=test",
        "vaultdoc.s3.bucket=test"
})
class PasskeyControllerIT {

    @DynamicPropertySource
    static void overrideRedisPassword(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    RecordingEmailSender mail;

    private String accessToken;

    @BeforeEach
    void seedActiveUser() throws Exception {
        String email = "pk-" + UUID.randomUUID() + "@example.com";

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery-staple","displayName":"PK"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        String token = mail.extractTokenFromLast(email);

        MvcResult verifyRes = mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        accessToken = json.readTree(verifyRes.getResponse().getContentAsString())
                .get("accessToken").asString();
    }

    @Test
    void begin_registration_requires_auth() throws Exception {
        mvc.perform(post("/api/v1/auth/passkey/register/begin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void begin_registration_returns_well_formed_options_when_authed() throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/passkey/register/begin")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeId").isString())
                .andExpect(jsonPath("$.publicKey.rp.id").value("localhost"))
                .andExpect(jsonPath("$.publicKey.rp.name").value("VaultDoc"))
                .andExpect(jsonPath("$.publicKey.challenge").isString())
                .andExpect(jsonPath("$.publicKey.user.name").isString())
                .andExpect(jsonPath("$.publicKey.attestation").value("none"))
                .andExpect(jsonPath("$.publicKey.pubKeyCredParams").isArray())
                .andReturn();

        // Round-trip the JSON to make sure it's a valid envelope.
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertNotNull(UUID.fromString(body.get("challengeId").asString()));
    }

    @Test
    void begin_signin_is_public_and_returns_options() throws Exception {
        mvc.perform(post("/api/v1/auth/passkey/signin/begin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeId").isString())
                .andExpect(jsonPath("$.publicKey.rpId").value("localhost"))
                .andExpect(jsonPath("$.publicKey.challenge").isString())
                .andExpect(jsonPath("$.publicKey.allowCredentials").isArray());
    }

    @Test
    void finish_registration_rejects_unknown_challenge_with_typed_problem() throws Exception {
        String body = """
                {
                  "challengeId": "%s",
                  "label": "x",
                  "credential": { "type": "public-key", "id": "x", "rawId": "x",
                    "response": { "attestationObject": "QQ", "clientDataJSON": "QQ" } }
                }
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/api/v1/auth/passkey/register/finish")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/passkey-challenge-expired"));
    }

    @Test
    void finish_signin_rejects_unknown_challenge_with_typed_problem() throws Exception {
        String body = """
                {
                  "challengeId": "%s",
                  "assertion": { "type": "public-key", "id": "x", "rawId": "QQ",
                    "response": { "authenticatorData": "QQ", "clientDataJSON": "QQ", "signature": "QQ" } }
                }
                """.formatted(UUID.randomUUID());

        mvc.perform(post("/api/v1/auth/passkey/signin/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/passkey-challenge-expired"));
    }

    @Test
    void list_passkeys_empty_for_new_user() throws Exception {
        mvc.perform(get("/api/v1/auth/passkey").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passkeys").isArray())
                .andExpect(jsonPath("$.passkeys.length()").value(0));
    }

    @Test
    void list_passkeys_requires_auth() throws Exception {
        mvc.perform(get("/api/v1/auth/passkey"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void delete_unknown_passkey_returns_typed_not_found() throws Exception {
        mvc.perform(delete("/api/v1/auth/passkey/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/passkey-not-found"));
    }

    @Test
    void begin_registration_challenge_is_unique_per_call() throws Exception {
        MvcResult a = mvc.perform(post("/api/v1/auth/passkey/register/begin")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn();
        MvcResult b = mvc.perform(post("/api/v1/auth/passkey/register/begin")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn();

        JsonNode aBody = json.readTree(a.getResponse().getContentAsString());
        JsonNode bBody = json.readTree(b.getResponse().getContentAsString());
        assertTrue(!aBody.get("challengeId").asString().equals(bBody.get("challengeId").asString()));
        assertTrue(!aBody.path("publicKey").path("challenge").asString().equals(
                bBody.path("publicKey").path("challenge").asString()));
    }
}
