package app.vaultdoc.gateway.auth;

import app.vaultdoc.gateway.support.RecordingEmailSender;
import app.vaultdoc.gateway.support.TestcontainersConfig;
import tools.jackson.databind.ObjectMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class PasswordResetFlowIT {

    @DynamicPropertySource
    static void overrideRedisPassword(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordingEmailSender mail;

    @Test
    void full_reset_flow_changes_password_and_revokes_old_sessions() throws Exception {
        String email = "reset-" + java.util.UUID.randomUUID() + "@example.com";
        String oldPw = "correct-horse-battery-staple";
        String newPw = "new-battery-horse-correct-staple";

        // 1. Register + verify
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + oldPw + "\"}"))
                .andExpect(status().isCreated());
        String vToken = mail.extractTokenFromLast(email);
        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + vToken + "\"}"))
                .andExpect(status().isOk());

        // 2. Get a session with the old password
        MvcResult loginRes = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + oldPw + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String oldRefreshToken = json.readTree(loginRes.getResponse().getContentAsString())
                .get("refreshToken").asString();

        // 3. Request reset
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isNoContent());

        // 4. Extract reset token from email
        String resetToken = mail.extractResetToken(email);

        // 5. Set new password
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + resetToken + "\",\"newPassword\":\"" + newPw + "\"}"))
                .andExpect(status().isNoContent());

        // 6. Old password no longer works
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + oldPw + "\"}"))
                .andExpect(status().isUnauthorized());

        // 7. New password works
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + newPw + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());

        // 8. Old refresh token is revoked (sessions revoked on reset)
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        // 9. Reset token is consumed — second use rejected
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + resetToken + "\",\"newPassword\":\"" + newPw + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/invalid-reset-token"));
    }

    @Test
    void forgot_password_unknown_email_returns_204_silent() throws Exception {
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ghost@example.com\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void forgot_password_rate_limit_returns_429_with_retry_after() throws Exception {
        String email = "rl-" + java.util.UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isCreated());
        String vTok = mail.extractTokenFromLast(email);
        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + vTok + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/reset-rate-limited"));
    }

    @Test
    void reset_with_garbage_token_returns_typed_400() throws Exception {
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-real-token\",\"newPassword\":\"new-password-long\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/invalid-reset-token"));
    }
}
