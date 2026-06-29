package app.vaultdoc.gateway.auth;

import app.vaultdoc.gateway.support.RecordingEmailSender;
import app.vaultdoc.gateway.support.TestcontainersConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class TwoFactorFlowIT {

    @DynamicPropertySource
    static void overrideRedisPassword(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordingEmailSender mail;

    @Test
    void full_2fa_flow_setup_then_login_with_totp_then_disable() throws Exception {
        String email = "2fa-" + java.util.UUID.randomUUID() + "@example.com";

        // 1. Register + verify
        String accessToken = registerAndVerify(email, mail);

        // 2. Login without 2FA → direct session
        loginExpectSession(email, accessToken);

        // 3. Setup 2FA begin → get otpauth URI
        MvcResult beginRes = mvc.perform(post("/api/v1/auth/2fa/setup/begin")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").isString())
                .andReturn();

        String otpauthUri = json.readTree(beginRes.getResponse().getContentAsString())
                .get("otpauthUri").asString();
        String secret = extractSecretFromUri(otpauthUri);

        // 4. Finish setup with a valid code
        String code = generateTotp(secret);
        MvcResult finishRes = mvc.perform(post("/api/v1/auth/2fa/setup/finish")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codes").isArray())
                .andExpect(jsonPath("$.codes.length()").value(10))
                .andReturn();

        String firstBackupCode = json.readTree(finishRes.getResponse().getContentAsString())
                .get("codes").get(0).asString();

        // 5. Login now returns a 2FA challenge
        MvcResult challengeRes = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactorToken").isString())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();
        String twoFactorToken = json.readTree(challengeRes.getResponse().getContentAsString())
                .get("twoFactorToken").asString();

        // 6. Verify with TOTP → full session
        String freshCode = generateTotp(secret);
        mvc.perform(post("/api/v1/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"twoFactorToken\":\"" + twoFactorToken + "\",\"code\":\"" + freshCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());

        // 7. Login again → challenge
        MvcResult challengeRes2 = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String twoFactorToken2 = json.readTree(challengeRes2.getResponse().getContentAsString())
                .get("twoFactorToken").asString();

        // 8. Use a backup code instead of TOTP → full session
        mvc.perform(post("/api/v1/auth/2fa/backup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"twoFactorToken\":\"" + twoFactorToken2 + "\","
                                + "\"backupCode\":\"" + firstBackupCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());

        // 9. Token reuse → 400
        mvc.perform(post("/api/v1/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"twoFactorToken\":\"" + twoFactorToken + "\",\"code\":\"" + freshCode + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/invalid-first-factor-token"));

        // 10. Backup codes status
        mvc.perform(get("/api/v1/auth/2fa/backup-codes")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(9)) // one was used
                .andExpect(jsonPath("$.total").value(10));

        // 11. Disable 2FA
        String disableCode = generateTotp(secret);
        mvc.perform(delete("/api/v1/auth/2fa")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + disableCode + "\"}"))
                .andExpect(status().isNoContent());

        // 12. Login now returns direct session again
        loginExpectSession(email, accessToken);
    }

    @Test
    void wrong_totp_returns_typed_problem() throws Exception {
        String email = "badtotp-" + java.util.UUID.randomUUID() + "@example.com";
        String accessToken = registerAndVerify(email, mail);

        MvcResult beginRes = mvc.perform(post("/api/v1/auth/2fa/setup/begin")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn();
        String secret = extractSecretFromUri(
                json.readTree(beginRes.getResponse().getContentAsString()).get("otpauthUri").asString());
        String validCode = generateTotp(secret);
        mvc.perform(post("/api/v1/auth/2fa/setup/finish")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + validCode + "\"}"))
                .andExpect(status().isOk());

        MvcResult challengeRes = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = json.readTree(challengeRes.getResponse().getContentAsString())
                .get("twoFactorToken").asString();

        mvc.perform(post("/api/v1/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"twoFactorToken\":\"" + token + "\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/invalid-totp-code"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String registerAndVerify(String email, RecordingEmailSender mail) throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isCreated());
        String verifyToken = mail.extractTokenFromLast(email);
        MvcResult res = mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + verifyToken + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("accessToken").asString();
    }

    private void loginExpectSession(String email, String oldToken) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(body.has("accessToken"),
                "Expected direct session but got: " + body);
    }

    private static String extractSecretFromUri(String uri) {
        // otpauth://totp/Label?secret=XXX&issuer=YYY
        for (String part : uri.split("[?&]")) {
            if (part.startsWith("secret=")) {
                return part.substring("secret=".length());
            }
        }
        throw new IllegalArgumentException("No secret in URI: " + uri);
    }

    private static String generateTotp(String secret) {
        try {
            DefaultCodeGenerator gen = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
            long period = System.currentTimeMillis() / 1000 / 30;
            return gen.generate(secret, period);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
