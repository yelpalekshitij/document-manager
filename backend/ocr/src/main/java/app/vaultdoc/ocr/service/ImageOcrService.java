package app.vaultdoc.ocr.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;
import app.vaultdoc.ocr.tesseract.TesseractException;
import app.vaultdoc.ocr.tesseract.TesseractLanguageResolver;
import app.vaultdoc.ocr.tesseract.TesseractRunner;
import app.vaultdoc.ocr.tesseract.TesseractUnavailableException;

/**
 * Raster OCR via Tesseract for JPEG / PNG / TIFF / WebP.
 *
 * <p>Order of checks is cheapest-first: byte cap → header probe (format +
 * dimensions, no raster decode) → pixel cap → language resolution →
 * tesseract. Every failure is returned as {@code FAILED} with a reason the
 * user can read; nothing is skipped silently.
 *
 * <p>Two inputs are decoded in the JVM after the pixel cap has passed:
 * <ul>
 *   <li>A JPEG whose EXIF Orientation isn't upright (phone photos) is rotated
 *       first — leptonica ignores EXIF, and sideways text OCRs as noise.</li>
 *   <li>A multi-frame TIFF (fax, multi-page scan) is split and OCR'd frame by
 *       frame, like scanned PDF pages: per-frame timeout, at most
 *       {@code maxFrames} frames, a per-document time budget, and a failed
 *       frame costs only that frame.</li>
 * </ul>
 */
public class ImageOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(ImageOcrService.class);

    static final int DEFAULT_MAX_FRAMES = 20;

    private final ObjectStorage storage;
    private final TesseractRunner runner;
    private final TesseractLanguageResolver languages;
    private final long maxImageBytes;
    private final long maxImagePixels;
    private final int extractedTextLimitBytes;
    private final Path tempRoot;
    private final int maxFrames;
    private final Duration documentBudget;
    private final Clock clock;

    public ImageOcrService(
            ObjectStorage storage,
            TesseractRunner runner,
            TesseractLanguageResolver languages,
            long maxImageBytes,
            long maxImagePixels,
            int extractedTextLimitBytes,
            Path tempRoot) {
        this(storage, runner, languages, maxImageBytes, maxImagePixels, extractedTextLimitBytes, tempRoot,
                DEFAULT_MAX_FRAMES, null, null);
    }

    /**
     * @param maxFrames      at most this many frames of a multi-frame TIFF are OCR'd
     * @param documentBudget wall-clock budget for all frames of one image; null for the default (10 min)
     * @param clock          time source for the budget; null for the system clock
     */
    public ImageOcrService(
            ObjectStorage storage,
            TesseractRunner runner,
            TesseractLanguageResolver languages,
            long maxImageBytes,
            long maxImagePixels,
            int extractedTextLimitBytes,
            Path tempRoot,
            int maxFrames,
            Duration documentBudget,
            Clock clock) {
        this.storage = storage;
        this.runner = runner;
        this.languages = languages;
        this.maxImageBytes = maxImageBytes;
        this.maxImagePixels = maxImagePixels;
        this.extractedTextLimitBytes = extractedTextLimitBytes;
        this.tempRoot = tempRoot;
        this.maxFrames = maxFrames > 0 ? maxFrames : DEFAULT_MAX_FRAMES;
        this.documentBudget = documentBudget == null ? ScannedPdfFallback.DEFAULT_DOCUMENT_BUDGET : documentBudget;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public OcrResult extract(StorageKey key, String mimeType) {
        if (mimeType == null || !mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return OcrResult.skipped("MIME type " + mimeType + " is not an image");
        }

        byte[] bytes;
        try {
            bytes = storage.download(key);
        } catch (RuntimeException e) {
            log.warn("Failed to download object for OCR key={} : {}", key.value(), e.toString());
            return OcrResult.failed("Could not download object: " + OcrText.summarize(e));
        }
        if (bytes == null || bytes.length == 0) {
            return OcrResult.failed("Image is empty (0 bytes)");
        }
        if (bytes.length > maxImageBytes) {
            return OcrResult.failed("Image is %d bytes, which exceeds the %d bytes OCR limit"
                    .formatted(bytes.length, maxImageBytes));
        }

        ImageDimensions dims;
        try {
            dims = ImageDimensions.probe(bytes);
        } catch (ImageDecodeException e) {
            return OcrResult.failed("Image could not be decoded: " + e.getMessage());
        }
        if (dims.pixels() > maxImagePixels) {
            return OcrResult.failed("Image is %d×%d (%.1f megapixels), which exceeds the %.1f megapixel OCR limit"
                    .formatted(dims.width(), dims.height(), dims.pixels() / 1e6, maxImagePixels / 1e6));
        }

        try {
            List<String> langs = languages.resolve();
            if (langs.isEmpty()) {
                return OcrResult.failed(noLanguageReason(languages));
            }
            try (TempWorkspace workspace = TempWorkspace.create(tempRoot)) {
                if (dims.frames() > 1) {
                    return ocrFrames(bytes, dims.frames(), langs, workspace, key);
                }
                Path input = stage(bytes, mimeType, workspace, key);
                String text = OcrText.clean(runner.recognize(input, langs));
                return OcrResult.done(OcrText.capToLimit(text, extractedTextLimitBytes), dims.frames());
            }
        } catch (TesseractException e) {
            log.warn("Tesseract OCR failed key={} : {}", key.value(), e.getMessage());
            return OcrResult.failed(OcrText.summarize(e));
        } catch (IOException e) {
            log.warn("OCR temp workspace failed key={} : {}", key.value(), e.toString());
            return OcrResult.failed("Could not stage image for OCR: " + OcrText.summarize(e));
        } catch (RuntimeException e) {
            log.error("Unexpected image OCR error key={}", key.value(), e);
            return OcrResult.failed("Unexpected OCR error: " + OcrText.summarize(e));
        }
    }

    /**
     * The bytes as tesseract should see them: unchanged, except a JPEG whose
     * EXIF says it is rotated/mirrored, which is re-encoded upright as PNG.
     */
    private Path stage(byte[] bytes, String mimeType, TempWorkspace workspace, StorageKey key) throws IOException {
        String ext = extensionFor(mimeType);
        if (".jpg".equals(ext)) {
            int orientation = ExifOrientation.read(bytes);
            if (orientation != 1) {
                BufferedImage decoded = decodeQuietly(bytes, key);
                if (decoded != null) {
                    Path upright = workspace.resolve("input.png");
                    ImageIO.write(ExifOrientation.apply(decoded, orientation), "png", upright.toFile());
                    return upright;
                }
            }
        }
        return workspace.write("input" + ext, bytes);
    }

    /** ImageIO can't read every JPEG (e.g. CMYK); then tesseract gets the original bytes, unrotated. */
    private static BufferedImage decodeQuietly(byte[] bytes, StorageKey key) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not decode JPEG to apply EXIF orientation key={} : {}", key.value(), e.toString());
            return null;
        }
    }

    private OcrResult ocrFrames(byte[] bytes, int frames, List<String> langs, TempWorkspace workspace, StorageKey key)
            throws IOException {
        PageOcrTally tally = new PageOcrTally(maxFrames, documentBudget);
        Instant deadline = clock.instant().plus(documentBudget);
        StringBuilder out = new StringBuilder();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return OcrResult.failed("Image could not be decoded: unrecognised image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, false, true);
                for (int i = 0; i < frames; i++) {
                    int pageNo = i + 1;
                    if (!tally.shouldAttempt(pageNo, !clock.instant().isBefore(deadline))) continue;
                    String text = ocrFrame(reader, i, langs, workspace, tally);
                    if (text != null && !text.isEmpty()) {
                        if (!out.isEmpty()) out.append("\n\n");
                        out.append(text);
                    }
                }
            } finally {
                reader.dispose();
            }
        }
        if (out.isEmpty() && tally.anyFailed()) {
            log.warn("Multi-page image OCR failed key={} : {}", key.value(), tally.failureReason("OCR"));
            return OcrResult.failed(tally.failureReason("OCR"));
        }
        List<String> notes = tally.notes("pages");
        if (!notes.isEmpty()) {
            log.warn("Multi-page image only partly searchable key={} : {}", key.value(), notes);
        }
        return OcrResult.done(OcrText.capToLimit(out.toString(), extractedTextLimitBytes), frames, String.join("; ", notes));
    }

    /** One frame → PNG → tesseract. Failures are recorded on the tally; returns null then. */
    private String ocrFrame(ImageReader reader, int index, List<String> langs, TempWorkspace workspace, PageOcrTally tally) {
        int pageNo = index + 1;
        Path png = workspace.resolve("frame-" + pageNo + ".png");
        try {
            BufferedImage frame = reader.read(index);
            ImageIO.write(frame, "png", png.toFile());
            frame.flush();
            return OcrText.clean(runner.recognize(png, langs));
        } catch (TesseractUnavailableException e) {
            tally.failed(pageNo, OcrText.summarize(e), true);
        } catch (TesseractException e) {
            tally.failed(pageNo, OcrText.summarize(e), false);
        } catch (IOException | RuntimeException e) {
            tally.failed(pageNo, "could not decode page: " + OcrText.summarize(e), false);
        } finally {
            try {
                Files.deleteIfExists(png);
            } catch (IOException ignored) {
                // the workspace is removed as a whole afterwards
            }
        }
        return null;
    }

    static String noLanguageReason(TesseractLanguageResolver languages) {
        return "No configured OCR language (" + String.join("+", languages.configured())
                + ") is installed for tesseract";
    }

    private static String extensionFor(String mimeType) {
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/tiff" -> ".tif";
            case "image/webp" -> ".webp";
            default -> ".img";
        };
    }
}
