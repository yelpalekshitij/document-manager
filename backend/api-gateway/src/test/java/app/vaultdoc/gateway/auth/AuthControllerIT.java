package app.vaultdoc.gateway.auth;

import app.vaultdoc.gateway.support.RecordingEmailSender;
import app.vaultdoc.gateway.support.TestcontainersConfig;
import tools.jackson.databind.JsonNode;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class AuthControllerIT {

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

    @Test
    void register_returns_201_with_verification_required_and_no_tokens() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@example.com","password":"correct-horse-battery-staple","displayName":"K"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("new@example.com"))
                .andExpect(jsonPath("$.user.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.verificationRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void full_loop_register_then_login_blocked_then_verify_then_login() throws Exception {
        String email = "loop@example.com";

        // 1. Register — 201
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery-staple","displayName":"Loop"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        // 2. Login → 403 unverified
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery-staple"}
                                """.formatted(email)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/unverified-account"));

        // 3. Pull the verification token from the captured email.
        String token = mail.extractTokenFromLast(email);

        // 4. Verify-email → 200 with AuthResponse
        MvcResult verifyRes = mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.status").value("ACTIVE"))
                .andReturn();
        JsonNode session = json.readTree(verifyRes.getResponse().getContentAsString());
        String accessToken = session.get("accessToken").asString();

        // 5. /me works with the issued bearer
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        // 6. Login now succeeds
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery-staple"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.status").value("ACTIVE"));

        // 7. The verification token is single-use — second consume is rejected.
        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/invalid-verification-token"));
    }

    @Test
    void resend_within_cooldown_returns_429_with_retry_after() throws Exception {
        String email = "resend@example.com";

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery-staple"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        // First resend triggers rate limit because register itself counts.
        mvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/verification-rate-limited"));
    }

    @Test
    void resend_for_unknown_email_returns_204_silent() throws Exception {
        mvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ghost@example.com\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void register_with_short_password_returns_400_problem() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"short@example.com","password":"too-short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void register_duplicate_email_returns_409() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dup@example.com","password":"correct-horse-battery-staple"}
                                """))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dup@example.com","password":"correct-horse-battery-staple"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Email already in use"));
    }

    @Test
    void login_with_wrong_password_returns_401_even_if_account_pending() throws Exception {
        // Wrong password is checked BEFORE the verification gate, so the response
        // is 401 invalid-credentials, not 403 unverified.
        String email = "wrong@example.com";
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery-staple"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"definitely-wrong-pw"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }
}
