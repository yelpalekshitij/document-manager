package app.vaultdoc.ocr.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import app.vaultdoc.ocr.tesseract.TesseractException;
import app.vaultdoc.ocr.tesseract.TesseractLanguageResolver;
import app.vaultdoc.ocr.tesseract.TesseractRunner;

/**
 * Renders PDF pages whose text layer is (near-)empty and OCRs them with
 * Tesseract. Used by {@link PdfBoxOcrService} page by page, so hybrid PDFs
 * (digital cover + scanned annexures) only pay for OCR on the scanned pages.
 *
 * <p>Bounded on every axis: at most {@code maxPages} pages per document, a
 * pixel cap checked from the page box <em>before</em> rendering (a 200-inch
 * page at 300 DPI would otherwise allocate gigabytes), and the runner's own
 * per-call timeout.
 */
public class ScannedPdfFallback {

    private final TesseractRunner runner;
    private final TesseractLanguageResolver languages;
    private final int maxPages;
    private final int dpi;
    private final long maxPixels;
    private final int minTextCharsPerPage;
    private final Path tempRoot;

    public ScannedPdfFallback(
            TesseractRunner runner,
            TesseractLanguageResolver languages,
            int maxPages,
            int dpi,
            long maxPixels,
            int minTextCharsPerPage,
            Path tempRoot) {
        this.runner = runner;
        this.languages = languages;
        this.maxPages = maxPages;
        this.dpi = dpi;
        this.maxPixels = maxPixels;
        this.minTextCharsPerPage = minTextCharsPerPage;
        this.tempRoot = tempRoot;
    }

    /** A page counts as scanned when its text layer has fewer visible characters than the threshold. */
    boolean isScanned(String pageText) {
        return OcrText.visibleChars(pageText) < minTextCharsPerPage;
    }

    int maxPages() {
        return maxPages;
    }

    TempWorkspace openWorkspace() throws IOException {
        return TempWorkspace.create(tempRoot);
    }

    /** Installed subset of the configured languages; never empty. */
    List<String> languages() throws PageOcrException {
        try {
            List<String> resolved = languages.resolve();
            if (resolved.isEmpty()) throw new PageOcrException(ImageOcrService.noLanguageReason(languages));
            return resolved;
        } catch (TesseractException e) {
            throw new PageOcrException(OcrText.summarize(e));
        }
    }

    /**
     * Render page {@code index} (0-based) to a grayscale PNG and OCR it.
     *
     * @throws PageOcrException with a user-readable reason on any failure
     */
    String ocrPage(PDFRenderer renderer, PDPage page, int index, List<String> langs, TempWorkspace workspace)
            throws PageOcrException {
        PDRectangle box = page.getCropBox();
        float scale = dpi / 72f;
        long width = (long) Math.max(Math.floor(box.getWidth() * scale), 1);
        long height = (long) Math.max(Math.floor(box.getHeight() * scale), 1);
        if (width * height > maxPixels) {
            throw new PageOcrException("rendered page would be %d×%d (%.1f megapixels) at %d DPI, exceeding the %.1f megapixel limit"
                    .formatted(width, height, width * height / 1e6, dpi, maxPixels / 1e6));
        }
        try {
            BufferedImage image = renderer.renderImageWithDPI(index, dpi, ImageType.GRAY);
            Path png = workspace.resolve("page-" + (index + 1) + ".png");
            ImageIO.write(image, "png", png.toFile());
            image.flush();
            return OcrText.clean(runner.recognize(png, langs));
        } catch (TesseractException e) {
            throw new PageOcrException(OcrText.summarize(e));
        } catch (IOException e) {
            throw new PageOcrException("could not render page: " + OcrText.summarize(e));
        }
    }

    /** A scanned page could not be OCR'd; the message is safe to show the user. */
    static final class PageOcrException extends Exception {
        PageOcrException(String message) {
            super(message);
        }
    }
}
