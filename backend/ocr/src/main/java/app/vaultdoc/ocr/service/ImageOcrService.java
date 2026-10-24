package app.vaultdoc.ocr.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageKey;
import app.vaultdoc.ocr.tesseract.TesseractException;
import app.vaultdoc.ocr.tesseract.TesseractLanguageResolver;
import app.vaultdoc.ocr.tesseract.TesseractRunner;

/**
 * Raster OCR via Tesseract for JPEG / PNG / TIFF / WebP.
 *
 * <p>Order of checks is cheapest-first and never decodes the raster in the
 * JVM: byte cap → header probe (format + dimensions) → pixel cap → language
 * resolution → tesseract. Every failure is returned as {@code FAILED} with a
 * reason the user can read; nothing is skipped silently.
 */
public class ImageOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(ImageOcrService.class);

    private final ObjectStorage storage;
    private final TesseractRunner runner;
    private final TesseractLanguageResolver languages;
    private final long maxImageBytes;
    private final long maxImagePixels;
    private final int extractedTextLimitBytes;
    private final Path tempRoot;

    public ImageOcrService(
            ObjectStorage storage,
            TesseractRunner runner,
            TesseractLanguageResolver languages,
            long maxImageBytes,
            long maxImagePixels,
            int extractedTextLimitBytes,
            Path tempRoot) {
        this.storage = storage;
        this.runner = runner;
        this.languages = languages;
        this.maxImageBytes = maxImageBytes;
        this.maxImagePixels = maxImagePixels;
        this.extractedTextLimitBytes = extractedTextLimitBytes;
        this.tempRoot = tempRoot;
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
                Path input = workspace.write("input" + extensionFor(mimeType), bytes);
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
