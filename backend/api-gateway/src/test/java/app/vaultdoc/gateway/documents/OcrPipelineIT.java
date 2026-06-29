package app.vaultdoc.gateway.documents;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.awaitility.Awaitility;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.vaultdoc.gateway.support.DocumentITBase;
import app.vaultdoc.gateway.support.RecordingEmailSender;

@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class OcrPipelineIT extends DocumentITBase {

    @DynamicPropertySource
    static void overrideRedisPassword(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordingEmailSender mail;

    @Test
    void pdf_upload_triggers_ocr_and_text_is_searchable() throws Exception {
        String token = registerVerifyAndLogin();
        byte[] pdf = newPdfWithText("Tax refund summary 2024 with passport details enclosed");

        String documentId = uploadAndFinalize(token, "passport-tax.pdf", "application/pdf", pdf);

        // Await OCR completion
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    JsonNode detail = getJsonAuth("/api/v1/documents/" + documentId, token);
                    assertEquals("DONE", detail.get("ocrStatus").asString(),
                            "expected ocrStatus=DONE, got " + detail);
                    assertTrue(detail.get("ocrText").asString().contains("refund"),
                            "expected ocrText to mention 'refund': " + detail.get("ocrText").asString());
                });

        // Search by a word from inside the PDF.
        mvc.perform(get("/api/v1/search").param("q", "refund")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].documentId").value(documentId));
    }

    @Test
    void corrupted_pdf_marks_ocr_failed_but_filename_search_still_works() throws Exception {
        String token = registerVerifyAndLogin();
        byte[] junk = "%PDF-1.4 totally bogus contents".getBytes(StandardCharsets.UTF_8);

        String documentId = uploadAndFinalize(token, "broken.pdf", "application/pdf", junk);

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    JsonNode detail = getJsonAuth("/api/v1/documents/" + documentId, token);
                    assertEquals("FAILED", detail.get("ocrStatus").asString(),
                            "expected ocrStatus=FAILED, got " + detail);
                });

        // Filename match still works.
        mvc.perform(get("/api/v1/search").param("q", "broken")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].documentId").value(documentId));
    }

    @Test
    void image_upload_is_marked_skipped_in_phase_1_3() throws Exception {
        String token = registerVerifyAndLogin();
        // Bytes don't actually have to be a valid JPEG since we never invoke an image decoder.
        byte[] bytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};

        String documentId = uploadAndFinalize(token, "scan.jpg", "image/jpeg", bytes);

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    JsonNode detail = getJsonAuth("/api/v1/documents/" + documentId, token);
                    assertEquals("SKIPPED", detail.get("ocrStatus").asString(),
                            "expected ocrStatus=SKIPPED, got " + detail);
                });
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String uploadAndFinalize(String token, String filename, String mime, byte[] bytes) throws Exception {
        String sha = sha256Hex(bytes);
        String initiateBody = """
                {"filename":"%s","mimeType":"%s","sizeBytes":%d,"sha256":"%s"}
                """.formatted(filename, mime, bytes.length, sha);
        MvcResult initiate = mvc.perform(post("/api/v1/documents/initiate-upload")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initiateBody))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode initiateJson = json.readTree(initiate.getResponse().getContentAsString());
        String documentId = initiateJson.get("documentId").asString();
        String uploadUrl = initiateJson.get("uploadUrl").asString();

        HttpResponse<String> putResp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .header("Content-Type", mime)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, putResp.statusCode(), "MinIO PUT response: " + putResp.body());

        mvc.perform(post("/api/v1/documents/finalize")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + documentId + "\"}"))
                .andExpect(status().isOk());
        return documentId;
    }

    private String registerVerifyAndLogin() throws Exception {
        String email = "ocr-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isCreated());
        String verifyToken = mail.extractTokenFromLast(email);
        MvcResult verify = mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + verifyToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(verify.getResponse().getContentAsString()).get("accessToken").asString();
    }

    private JsonNode getJsonAuth(String path, String token) throws Exception {
        MvcResult result = mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] newPdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
