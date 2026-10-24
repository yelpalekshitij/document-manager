package app.vaultdoc.ocr.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import lombok.With;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vaultdoc.ocr.*}. Out-of-range values fall back to the defaults rather
 * than failing boot — a mistyped DPI should not take the OCR worker down.
 *
 * @param consumerGroup Consumer-group name on the upload-finalized stream.
 * @param consumerName Optional explicit consumer name. Defaults to hostname + random suffix.
 * @param batchSize How many messages to fetch per XREADGROUP call.
 * @param pollTimeout Block timeout per XREADGROUP poll.
 * @param extractedTextLimitBytes Max bytes of extracted text stored on the document row.
 * @param languages Tesseract languages, {@code +}-separated. Missing packs degrade to the installed subset.
 * @param tesseractPath Tesseract executable — a bare name resolved on PATH, or an absolute path.
 * @param tessdataDir Optional {@code --tessdata-dir}; null uses tesseract's compiled-in default.
 * @param tesseractTimeout Wall-clock budget per tesseract invocation (one image / one PDF page).
 * @param maxImageBytes Images larger than this are FAILED before any decoding.
 * @param maxImagePixels Images (or rendered PDF pages) above this pixel count are FAILED — guards against decompression bombs.
 * @param pdfOcrFallbackEnabled Render + OCR PDF pages whose text layer is (near-)empty.
 * @param pdfOcrMaxPages At most this many scanned pages per PDF are OCR'd.
 * @param pdfOcrDpi Render resolution for scanned PDF pages (72..600).
 * @param pdfMinTextCharsPerPage A PDF page with fewer non-whitespace characters than this is treated as scanned.
 * @param tempDir Parent directory for per-job scratch directories; null uses java.io.tmpdir.
 */
@With
@ConfigurationProperties(prefix = "vaultdoc.ocr")
public record OcrProperties(
        String consumerGroup,
        String consumerName,
        int batchSize,
        Duration pollTimeout,
        int extractedTextLimitBytes,
        String languages,
        String tesseractPath,
        String tessdataDir,
        Duration tesseractTimeout,
        long maxImageBytes,
        long maxImagePixels,
        Boolean pdfOcrFallbackEnabled,
        int pdfOcrMaxPages,
        int pdfOcrDpi,
        int pdfMinTextCharsPerPage,
        String tempDir) {

    static final String DEFAULT_LANGUAGES = "eng+hin";
    static final Duration DEFAULT_TESSERACT_TIMEOUT = Duration.ofSeconds(60);
    static final long DEFAULT_MAX_IMAGE_BYTES = 25L * 1024 * 1024;
    static final long DEFAULT_MAX_IMAGE_PIXELS = 50_000_000L;
    static final int DEFAULT_PDF_OCR_MAX_PAGES = 20;
    static final int DEFAULT_PDF_OCR_DPI = 300;
    static final int DEFAULT_PDF_MIN_TEXT_CHARS_PER_PAGE = 25;

    /**
     * Tesseract language codes ({@code eng}, {@code chi_sim}). Anything else is
     * dropped: the codes end up as process arguments, so a value like
     * {@code -c} or {@code ../x} must never reach the command line.
     */
    private static final Pattern LANGUAGE_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");

    public OcrProperties {
        if (consumerGroup == null || consumerGroup.isBlank()) {
            consumerGroup = "vaultdoc-ocr-consumers";
        }
        if (batchSize <= 0) batchSize = 10;
        if (pollTimeout == null) pollTimeout = Duration.ofSeconds(2);
        if (extractedTextLimitBytes <= 0) extractedTextLimitBytes = 2_000_000;
        if (languages == null || languages.isBlank()) languages = DEFAULT_LANGUAGES;
        if (tesseractPath == null || tesseractPath.isBlank()) tesseractPath = "tesseract";
        if (tessdataDir != null && tessdataDir.isBlank()) tessdataDir = null;
        if (tesseractTimeout == null || tesseractTimeout.isZero() || tesseractTimeout.isNegative()) {
            tesseractTimeout = DEFAULT_TESSERACT_TIMEOUT;
        }
        if (maxImageBytes <= 0) maxImageBytes = DEFAULT_MAX_IMAGE_BYTES;
        if (maxImagePixels <= 0) maxImagePixels = DEFAULT_MAX_IMAGE_PIXELS;
        if (pdfOcrFallbackEnabled == null) pdfOcrFallbackEnabled = Boolean.TRUE;
        if (pdfOcrMaxPages <= 0) pdfOcrMaxPages = DEFAULT_PDF_OCR_MAX_PAGES;
        if (pdfOcrDpi < 72 || pdfOcrDpi > 600) pdfOcrDpi = DEFAULT_PDF_OCR_DPI;
        if (pdfMinTextCharsPerPage <= 0) pdfMinTextCharsPerPage = DEFAULT_PDF_MIN_TEXT_CHARS_PER_PAGE;
        if (tempDir != null && tempDir.isBlank()) tempDir = null;
    }

    /** Every knob at its default — what an empty {@code vaultdoc.ocr} block binds to. */
    public static OcrProperties defaults() {
        return new OcrProperties(null, null, 0, null, 0, null, null, null, null, 0, 0, null, 0, 0, 0, null);
    }

    /**
     * {@link #languages()} split on {@code +}, {@code ,} or whitespace; trimmed,
     * de-duplicated, invalid codes dropped. Never empty — falls back to {@code eng}.
     */
    public List<String> languageList() {
        Set<String> out = new LinkedHashSet<>();
        for (String token : languages.split("[+,\\s]+")) {
            if (LANGUAGE_CODE.matcher(token).matches()) out.add(token);
        }
        return out.isEmpty() ? List.of("eng") : List.copyOf(out);
    }

    public String resolvedConsumerName() {
        if (consumerName != null && !consumerName.isBlank()) return consumerName;
        String host = System.getenv().getOrDefault("HOSTNAME", "local");
        return "ocr-" + host + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
