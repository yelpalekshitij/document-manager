package app.vaultdoc.gateway.documents;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.vaultdoc.catalog.service.DocumentService;
import app.vaultdoc.core.pipeline.stream.StreamNames;
import app.vaultdoc.gateway.support.DocumentITBase;
import app.vaultdoc.gateway.support.RecordingEmailSender;
import app.vaultdoc.ocr.config.OcrProperties;
import app.vaultdoc.ocr.consumer.PendingEntryReclaimer;

@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class OcrPipelineIT extends DocumentITBase {

    /** Real tesseract, if this host has one. Tests that need it are skipped otherwise. */
    private static final Optional<String> TESSERACT = locateTesseract();

    @DynamicPropertySource
    static void overrideRedisPassword(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.password", () -> "");
        // Gradle daemons don't always inherit the interactive PATH (e.g. /opt/homebrew/bin).
        TESSERACT.ifPresent(path -> registry.add("vaultdoc.ocr.tesseract-path", () -> path));
        // Reclaim tests drive sweeps by hand: tiny idle threshold, no background sweeps.
        registry.add("vaultdoc.ocr.reclaim-min-idle", () -> "1s");
        registry.add("vaultdoc.ocr.reclaim-interval", () -> "1h");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordingEmailSender mail;
    @Autowired StringRedisTemplate redis;
    @Autowired PendingEntryReclaimer reclaimer;
    @Autowired OcrProperties ocrProperties;
    @Autowired DocumentService documentService;
    @Autowired @Qualifier("ocrStreamListenerContainer") StreamMessageListenerContainer<?, ?> ocrListener;

    // ── stranded stream entries (worker died mid-job) ────────────────────────

    @Test
    void pending_entry_of_dead_consumer_is_reclaimed() throws Exception {
        String token = registerVerifyAndLogin();
        String documentId = strandEntry(token, "orphan.pdf", "Orphaned lease agreement for flat 4B", 1);
        assertEquals("PROCESSING", getJsonAuth("/api/v1/documents/" + documentId, token).get("ocrStatus").asString());

        awaitIdleThenReclaim();

        JsonNode detail = awaitOcr(documentId, token, "DONE");
        assertTrue(detail.get("ocrText").asString().contains("Orphaned lease"), "ocrText: " + detail.get("ocrText"));
        assertEquals(0L, pendingCount(), "the reclaimed entry must be acked");
    }

    @Test
    void entry_redelivered_too_often_is_dead_lettered() throws Exception {
        String token = registerVerifyAndLogin();
        long dlqBefore = streamLength(StreamNames.UPLOAD_FINALIZED_DLQ);
        String documentId = strandEntry(token, "poison.pdf", "This file keeps crashing the worker", ocrProperties.maxDeliveries());

        awaitIdleThenReclaim();

        JsonNode detail = awaitOcr(documentId, token, "FAILED");
        assertTrue(detail.get("ocrFailureReason").asString().contains("delivery attempts"), "reason: " + detail);
        assertEquals(0L, pendingCount(), "the poison entry must be acked");
        assertEquals(dlqBefore + 1, streamLength(StreamNames.UPLOAD_FINALIZED_DLQ));
    }

    /**
     * Upload with the live listener paused, then let a "dead" consumer read the
     * entry {@code deliveries} times without acking — as if its JVM died mid-OCR.
     */
    private String strandEntry(String token, String filename, String text, int deliveries) throws Exception {
        ocrListener.stop();
        try {
            // Let an in-flight XREADGROUP (≤ pollTimeout) finish so it can't grab our entry.
            Awaitility.await().pollDelay(ocrProperties.pollTimeout().plusMillis(500)).until(() -> true);
            String documentId = uploadAndFinalize(token, filename, "application/pdf", newPdfWithText(text));
            Consumer dead = Consumer.from(ocrProperties.consumerGroup(), "ocr-dead-worker-" + UUID.randomUUID());
            var taken = redis.opsForStream().read(dead, StreamReadOptions.empty().count(10),
                    StreamOffset.create(StreamNames.UPLOAD_FINALIZED, ReadOffset.lastConsumed()));
            assertTrue(taken.stream().anyMatch(r -> documentId.equals(String.valueOf(r.getValue().get("documentId")))),
                    "dead worker should have taken the entry: " + taken);
            for (int i = 1; i < deliveries; i++) {
                // Re-reading its own pending entries ("0") is a redelivery: bumps the count.
                redis.opsForStream().read(dead, StreamReadOptions.empty().count(10),
                        StreamOffset.create(StreamNames.UPLOAD_FINALIZED, ReadOffset.from("0")));
            }
            documentService.markOcrProcessing(UUID.fromString(documentId));
            return documentId;
        } finally {
            ocrListener.start();
        }
    }

    private void awaitIdleThenReclaim() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .until(() -> reclaimer.reclaim() > 0);
    }

    private long pendingCount() {
        return redis.opsForStream().pending(StreamNames.UPLOAD_FINALIZED, ocrProperties.consumerGroup())
                .getTotalPendingMessages();
    }

    private long streamLength(String stream) {
        Long size = redis.opsForStream().size(stream);
        return size == null ? 0 : size;
    }

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
    void png_upload_is_ocrd_and_text_is_searchable() throws Exception {
        requireTesseract();
        String token = registerVerifyAndLogin();
        byte[] png = png(textImage("INVOICE PAID", 1400, 220));

        String documentId = uploadAndFinalize(token, "receipt.png", "image/png", png);

        JsonNode detail = awaitOcr(documentId, token, "DONE");
        assertTrue(detail.get("ocrText").asString().contains("INVOICE"),
                "expected OCR text to contain INVOICE: " + detail);
        assertEquals(1, detail.get("ocrPageCount").asInt());

        // Searchable by a word that only exists inside the image.
        mvc.perform(get("/api/v1/search").param("q", "invoice")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].documentId").value(documentId));
    }

    @Test
    void scanned_pdf_is_ocrd_via_fallback() throws Exception {
        requireTesseract();
        String token = registerVerifyAndLogin();
        byte[] scanned = imageOnlyPdf(textImage("LEASE DEED", 500, 100));

        String documentId = uploadAndFinalize(token, "scan.pdf", "application/pdf", scanned);

        JsonNode detail = awaitOcr(documentId, token, "DONE");
        assertTrue(detail.get("ocrText").asString().contains("LEASE"),
                "expected fallback OCR text to contain LEASE: " + detail);

        mvc.perform(get("/api/v1/search").param("q", "lease")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].documentId").value(documentId));
    }

    @Test
    void corrupt_image_marks_ocr_failed_with_reason() throws Exception {
        String token = registerVerifyAndLogin();
        // A JPEG SOI + APP0 marker and nothing else: the header probe rejects it
        // before tesseract is ever involved, so this runs without the binary.
        byte[] bytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};

        String documentId = uploadAndFinalize(token, "scan.jpg", "image/jpeg", bytes);

        JsonNode detail = awaitOcr(documentId, token, "FAILED");
        assertTrue(detail.get("ocrFailureReason").asString().contains("could not be decoded"),
                "expected a decode failure reason: " + detail);
    }

    @Test
    void unsupported_image_type_is_skipped_with_reason() throws Exception {
        String token = registerVerifyAndLogin();
        byte[] heic = "....ftypheic not really".getBytes(StandardCharsets.US_ASCII);

        String documentId = uploadAndFinalize(token, "photo.heic", "image/heic", heic);

        JsonNode detail = awaitOcr(documentId, token, "SKIPPED");
        assertTrue(detail.get("ocrFailureReason").asString().contains("image/heic"),
                "expected the skip reason to name the type: " + detail);
    }

    @Test
    void ocr_text_is_tenant_scoped() throws Exception {
        String owner = registerVerifyAndLogin();
        String stranger = registerVerifyAndLogin();
        String marker = "zebracrossing" + UUID.randomUUID().toString().substring(0, 8);
        String documentId = uploadAndFinalize(owner, "private.pdf", "application/pdf",
                newPdfWithText("Confidential " + marker + " medical record"));
        awaitOcr(documentId, owner, "DONE");

        mvc.perform(get("/api/v1/search").param("q", marker)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].documentId").value(documentId));
        mvc.perform(get("/api/v1/search").param("q", marker)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/documents/" + documentId)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound());
    }

    // ── OCR × tags (Phase 1.4 integration) ─────────────────────────────────
    // Tags are set right after finalize, i.e. while tesseract is still running,
    // so the consumer's later re-index must merge into the ES doc, not replace it.

    @Test
    void tagged_image_keeps_tags_after_tesseract_reindex() throws Exception {
        requireTesseract();
        String token = registerVerifyAndLogin();
        String tagId = createTag(token, "receipts");
        byte[] png = png(textImage("ZEBRAFISH TOTAL", 1400, 220));

        String documentId = uploadAndFinalize(token, "till.png", "image/png", png);
        setTags(token, documentId, tagId);

        JsonNode detail = awaitOcr(documentId, token, "DONE");
        assertEquals("receipts", detail.get("tags").get(0).get("name").asString());
        // A word only tesseract can see, filtered by the tag: both halves must be in the index.
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> mvc.perform(get("/api/v1/search").param("q", "zebrafish")
                                .param("tagIds", tagId)
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(1))
                        .andExpect(jsonPath("$.items[0].documentId").value(documentId))
                        .andExpect(jsonPath("$.items[0].tagIds[0]").value(tagId)));
    }

    @Test
    void skipped_image_keeps_tags_and_reason() throws Exception {
        String token = registerVerifyAndLogin();
        String tagId = createTag(token, "photos");
        byte[] heic = "....ftypheic not really".getBytes(StandardCharsets.US_ASCII);

        String documentId = uploadAndFinalize(token, "holiday.heic", "image/heic", heic);
        setTags(token, documentId, tagId);

        JsonNode detail = awaitOcr(documentId, token, "SKIPPED");
        assertTrue(detail.get("ocrFailureReason").asString().contains("image/heic"),
                "expected the skip reason to survive alongside tags: " + detail);
        assertEquals("photos", detail.get("tags").get(0).get("name").asString());
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> mvc.perform(get("/api/v1/search").param("q", "holiday")
                                .param("tagIds", tagId)
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].documentId").value(documentId)));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String createTag(String token, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/tags")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asString();
    }

    private void setTags(String token, String documentId, String... tagIds) throws Exception {
        String ids = String.join("\",\"", tagIds);
        mvc.perform(put("/api/v1/documents/" + documentId + "/tags")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\":[\"" + ids + "\"]}"))
                .andExpect(status().isOk());
    }

    private JsonNode awaitOcr(String documentId, String token, String expectedStatus) {
        AtomicReference<JsonNode> last = new AtomicReference<>();
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    JsonNode detail = getJsonAuth("/api/v1/documents/" + documentId, token);
                    last.set(detail);
                    assertEquals(expectedStatus, detail.get("ocrStatus").asString(),
                            "expected ocrStatus=" + expectedStatus + ", got " + detail);
                });
        return last.get();
    }

    private static BufferedImage textImage(String text, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, height / 4));
            g.drawString(text, 20, height / 2 + height / 8);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** A PDF with no text layer at all — just the picture of a page. */
    private static byte[] imageOnlyPdf(BufferedImage image) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                PDImageXObject xobj = LosslessFactory.createFromImage(doc, image);
                content.drawImage(xobj, 50, 400, image.getWidth(), image.getHeight());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Skips a real-engine test when tesseract isn't installed — unless
     * {@code CI_REQUIRE_TESSERACT=true} (set by CI after installing it), where a
     * missing binary must fail the build rather than turn into a green skip.
     */
    private static void requireTesseract() {
        if (TESSERACT.isPresent()) return;
        String required = System.getenv("CI_REQUIRE_TESSERACT");
        if (required != null && required.strip().equalsIgnoreCase("true")) {
            org.junit.jupiter.api.Assertions.fail("CI_REQUIRE_TESSERACT=true but no tesseract binary was found");
        }
        assumeTrue(false, "tesseract not installed — skipping real-engine IT");
    }

    private static Optional<String> locateTesseract() {
        List<String> candidates = new ArrayList<>();
        String env = System.getenv("TESSERACT_PATH");
        if (env != null && !env.isBlank()) candidates.add(env);
        candidates.addAll(List.of("tesseract", "/opt/homebrew/bin/tesseract", "/usr/local/bin/tesseract", "/usr/bin/tesseract"));
        for (String candidate : candidates) {
            try {
                Process p = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) return Optional.of(candidate);
                p.destroyForcibly();
            } catch (Exception ignored) {
                // try the next candidate
            }
        }
        return Optional.empty();
    }

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
