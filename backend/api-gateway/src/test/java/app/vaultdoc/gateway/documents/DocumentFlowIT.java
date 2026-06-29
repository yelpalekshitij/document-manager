package app.vaultdoc.gateway.documents;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.vaultdoc.gateway.support.DocumentITBase;
import app.vaultdoc.gateway.support.RecordingEmailSender;

@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class DocumentFlowIT extends DocumentITBase {

    @DynamicPropertySource
    static void overrideRedisPassword(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordingEmailSender mail;

    @Test
    void fullUploadFlow_registerVerifyInitiateFinalizeListGetDownloadSearchDelete() throws Exception {
        String email = "docs-" + UUID.randomUUID() + "@example.com";
        String password = "correct-horse-battery-staple";

        // 1) Register
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RegisterReq(email, password, "K"))))
                .andExpect(status().isCreated());

        // 2) Verify email (recover token from the captured mail)
        String verifyToken = mail.extractTokenFromLast(email);
        String accessToken = postJson("/api/v1/auth/verify-email", "{\"token\":\"" + verifyToken + "\"}")
                .get("accessToken").asString();

        // 3) Initiate upload
        byte[] body = "%PDF-1.4 fake pdf content".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(body);
        var initiateReq = """
                {"filename":"passport.pdf","mimeType":"application/pdf","sizeBytes":%d,"sha256":"%s"}
                """.formatted(body.length, sha);
        JsonNode initiate = postJsonAuth("/api/v1/documents/initiate-upload", initiateReq, accessToken, 201);
        String documentId = initiate.get("documentId").asString();
        String uploadUrl = initiate.get("uploadUrl").asString();

        // 4) PUT bytes to the presigned URL
        HttpResponse<String> putResp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .header("Content-Type", "application/pdf")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, putResp.statusCode(), "MinIO PUT response: " + putResp.body());

        // 5) Finalize
        JsonNode finalized = postJsonAuth("/api/v1/documents/finalize",
                "{\"documentId\":\"" + documentId + "\"}", accessToken, 200);
        assertEquals("READY", finalized.get("status").asString());
        assertEquals(body.length, finalized.get("sizeBytes").asLong());

        // 6) List
        mvc.perform(get("/api/v1/documents").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(documentId))
                .andExpect(jsonPath("$.totalCount").value(1));

        // 7) Get detail
        mvc.perform(get("/api/v1/documents/" + documentId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("passport.pdf"));

        // 8) Download (presigned)
        MvcResult dl = mvc.perform(get("/api/v1/documents/" + documentId + "/download")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        String downloadUrl = json.readTree(dl.getResponse().getContentAsString()).get("downloadUrl").asString();
        HttpResponse<byte[]> getResp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, getResp.statusCode());
        assertEquals(body.length, getResp.body().length);

        // 9) Search by filename
        mvc.perform(get("/api/v1/search").param("q", "passport")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].documentId").value(documentId));

        // 10) Folder tree contains the 8 seeded folders
        mvc.perform(get("/api/v1/folders").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folders.length()").value(8));

        // 11) Delete
        mvc.perform(delete("/api/v1/documents/" + documentId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/documents").header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    void initiateUpload_rejectsDisallowedMime() throws Exception {
        String accessToken = registerVerifyAndLogin();
        String reqBody = """
                {"filename":"hack.exe","mimeType":"application/octet-stream","sizeBytes":10,"sha256":"%s"}
                """.formatted("a".repeat(64));
        mvc.perform(post("/api/v1/documents/initiate-upload")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/mime-not-allowed"));
    }

    @Test
    void initiateUpload_rejectsOversize() throws Exception {
        String accessToken = registerVerifyAndLogin();
        String reqBody = """
                {"filename":"huge.pdf","mimeType":"application/pdf","sizeBytes":1000000000,"sha256":"%s"}
                """.formatted("a".repeat(64));
        mvc.perform(post("/api/v1/documents/initiate-upload")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://vaultdoc.app/problems/size-exceeded"));
    }

    @Test
    void otherUserCannotSeeMyDocument() throws Exception {
        String myToken = registerVerifyAndLogin();
        String theirToken = registerVerifyAndLogin();

        // I upload one doc
        byte[] body = "%PDF-1.4 mine".getBytes(StandardCharsets.UTF_8);
        var initiateReq = """
                {"filename":"mine.pdf","mimeType":"application/pdf","sizeBytes":%d,"sha256":"%s"}
                """.formatted(body.length, sha256Hex(body));
        JsonNode initiate = postJsonAuth("/api/v1/documents/initiate-upload", initiateReq, myToken, 201);
        String myDocId = initiate.get("documentId").asString();
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(initiate.get("uploadUrl").asString()))
                        .header("Content-Type", "application/pdf")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        postJsonAuth("/api/v1/documents/finalize",
                "{\"documentId\":\"" + myDocId + "\"}", myToken, 200);

        // The other user can't see it
        mvc.perform(get("/api/v1/documents/" + myDocId).header("Authorization", "Bearer " + theirToken))
                .andExpect(status().isNotFound());
        // …or find it in search
        mvc.perform(get("/api/v1/search").param("q", "mine")
                        .header("Authorization", "Bearer " + theirToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
    }

    private String registerVerifyAndLogin() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RegisterReq(email, "correct-horse-battery-staple", null))))
                .andExpect(status().isCreated());
        String verifyToken = mail.extractTokenFromLast(email);
        return postJson("/api/v1/auth/verify-email", "{\"token\":\"" + verifyToken + "\"}")
                .get("accessToken").asString();
    }

    private JsonNode postJson(String path, String body) throws Exception {
        MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postJsonAuth(String path, String body, String token, int expectStatus) throws Exception {
        MvcResult result = mvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectStatus))
                .andReturn();
        String resp = result.getResponse().getContentAsString();
        assertTrue(!resp.isBlank(), "Expected JSON body, got empty");
        return json.readTree(resp);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record RegisterReq(String email, String password, String displayName) {}
}
