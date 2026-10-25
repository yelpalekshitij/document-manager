package app.vaultdoc.ocr.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import app.vaultdoc.ocr.tesseract.TesseractException;
import app.vaultdoc.ocr.tesseract.TesseractLanguageResolver;
import app.vaultdoc.ocr.tesseract.TesseractRunner;
import app.vaultdoc.ocr.tesseract.TesseractUnavailableException;

/**
 * Renders PDF pages whose text layer is (near-)empty and OCRs them with
 * Tesseract. Used by {@link PdfBoxOcrService} page by page, so hybrid PDFs
 * (digital cover + scanned annexures) only pay for OCR on the scanned pages.
 *
 * <p>Bounded on every axis: at most {@code maxPages} pages per document, a
 * wall-clock budget per document (so one PDF can't hold the single-threaded
 * consumer for {@code maxPages × timeout}), a pixel cap checked from the page
 * box <em>and</em> every embedded image's declared size <em>before</em>
 * rendering (a 200-inch page, or a Letter page holding a 60000² image, would
 * otherwise allocate gigabytes), and the runner's own per-call timeout.
 */
public class ScannedPdfFallback {

    static final Duration DEFAULT_DOCUMENT_BUDGET = Duration.ofMinutes(10);

    private final TesseractRunner runner;
    private final TesseractLanguageResolver languages;
    private final int maxPages;
    private final int dpi;
    private final long maxPixels;
    private final int minTextCharsPerPage;
    private final Path tempRoot;
    private final Duration documentBudget;
    private final Clock clock;

    public ScannedPdfFallback(
            TesseractRunner runner,
            TesseractLanguageResolver languages,
            int maxPages,
            int dpi,
            long maxPixels,
            int minTextCharsPerPage,
            Path tempRoot) {
        this(runner, languages, maxPages, dpi, maxPixels, minTextCharsPerPage, tempRoot, null, null);
    }

    /**
     * @param documentBudget wall-clock budget for all OCR of one document; null for the default (10 min)
     * @param clock          time source for the budget; null for the system clock
     */
    public ScannedPdfFallback(
            TesseractRunner runner,
            TesseractLanguageResolver languages,
            int maxPages,
            int dpi,
            long maxPixels,
            int minTextCharsPerPage,
            Path tempRoot,
            Duration documentBudget,
            Clock clock) {
        this.runner = runner;
        this.languages = languages;
        this.maxPages = maxPages;
        this.dpi = dpi;
        this.maxPixels = maxPixels;
        this.minTextCharsPerPage = minTextCharsPerPage;
        this.tempRoot = tempRoot;
        this.documentBudget = documentBudget == null ? DEFAULT_DOCUMENT_BUDGET : documentBudget;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** A page counts as scanned when its text layer has fewer visible characters than the threshold. */
    boolean isScanned(String pageText) {
        return OcrText.visibleChars(pageText) < minTextCharsPerPage;
    }

    /** Per-document state: lazily resolved languages, renderer and scratch dir, plus the deadline. */
    Session open(PDDocument doc) {
        return new Session(doc);
    }

    final class Session implements AutoCloseable {

        private final PDDocument doc;
        private final Instant deadline;
        private final PageOcrTally tally;
        private List<String> langs;
        private PDFRenderer renderer;
        private TempWorkspace workspace;

        private Session(PDDocument doc) {
            this.doc = doc;
            this.deadline = clock.instant().plus(documentBudget);
            this.tally = new PageOcrTally(maxPages, documentBudget);
        }

        PageOcrTally tally() {
            return tally;
        }

        /**
         * OCR page {@code index} (0-based) if the cap, budget and earlier
         * failures allow it. Returns null when the page was not OCR'd — the
         * tally records why.
         */
        String ocrIfAllowed(int index) {
            int pageNo = index + 1;
            if (!tally.shouldAttempt(pageNo, !clock.instant().isBefore(deadline))) return null;
            try {
                return ocr(index);
            } catch (PageOcrException e) {
                tally.failed(pageNo, e.getMessage(), e.documentWide());
                return null;
            }
        }

        private String ocr(int index) throws PageOcrException {
            if (langs == null) langs = languages();
            PDPage page = doc.getPage(index);
            checkPixelBudget(page);
            if (workspace == null) workspace = openWorkspace();
            if (renderer == null) {
                renderer = new PDFRenderer(doc);
                // Decode images no larger than the render needs — bounds memory for
                // inline images the XObject pre-check can't see.
                renderer.setSubsamplingAllowed(true);
            }
            return render(renderer, index, langs, workspace);
        }

        @Override
        public void close() {
            if (workspace != null) workspace.close();
        }
    }

    /** Installed subset of the configured languages; never empty. */
    private List<String> languages() throws PageOcrException {
        try {
            List<String> resolved = languages.resolve();
            if (resolved.isEmpty()) throw new PageOcrException(ImageOcrService.noLanguageReason(languages), true);
            return resolved;
        } catch (TesseractException e) {
            throw new PageOcrException(OcrText.summarize(e), true);
        }
    }

    private TempWorkspace openWorkspace() throws PageOcrException {
        try {
            return TempWorkspace.create(tempRoot);
        } catch (IOException e) {
            throw new PageOcrException("could not create a temp workspace for OCR: " + OcrText.summarize(e), true);
        }
    }

    private void checkPixelBudget(PDPage page) throws PageOcrException {
        PDRectangle box = page.getCropBox();
        float scale = dpi / 72f;
        long width = (long) Math.max(Math.floor(box.getWidth() * scale), 1);
        long height = (long) Math.max(Math.floor(box.getHeight() * scale), 1);
        if (width * height > maxPixels) {
            throw new PageOcrException("rendered page would be %d×%d (%.1f megapixels) at %d DPI, exceeding the %.1f megapixel limit"
                    .formatted(width, height, width * height / 1e6, dpi, maxPixels / 1e6), false);
        }
        try {
            checkEmbeddedImages(page.getResources(), new HashSet<>());
        } catch (IOException e) {
            throw new PageOcrException("could not read the page's images: " + OcrText.summarize(e), false);
        }
    }

    /** Declared size of every image XObject, recursing into form XObjects (cycle-safe). */
    private void checkEmbeddedImages(PDResources resources, Set<COSBase> visited) throws IOException, PageOcrException {
        if (resources == null) return;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobject = resources.getXObject(name);
            if (xobject instanceof PDImageXObject image) {
                long pixels = (long) image.getWidth() * image.getHeight();
                if (pixels > maxPixels) {
                    throw new PageOcrException("embedded image is %d×%d (%.1f megapixels), exceeding the %.1f megapixel limit"
                            .formatted(image.getWidth(), image.getHeight(), pixels / 1e6, maxPixels / 1e6), false);
                }
            } else if (xobject instanceof PDFormXObject form && visited.add(form.getCOSObject())) {
                checkEmbeddedImages(form.getResources(), visited);
            }
        }
    }

    /** Render page {@code index} (0-based) to a grayscale PNG and OCR it. */
    private String render(PDFRenderer renderer, int index, List<String> langs, TempWorkspace workspace)
            throws PageOcrException {
        try {
            BufferedImage image = renderer.renderImageWithDPI(index, dpi, ImageType.GRAY);
            Path png = workspace.resolve("page-" + (index + 1) + ".png");
            ImageIO.write(image, "png", png.toFile());
            image.flush();
            return OcrText.clean(runner.recognize(png, langs));
        } catch (TesseractUnavailableException e) {
            throw new PageOcrException(OcrText.summarize(e), true);
        } catch (TesseractException e) {
            throw new PageOcrException(OcrText.summarize(e), false);
        } catch (IOException e) {
            throw new PageOcrException("could not render page: " + OcrText.summarize(e), false);
        }
    }

    /**
     * A scanned page could not be OCR'd; the message is safe to show the user.
     * {@code documentWide} failures (engine/environment) stop OCR for the rest
     * of the document instead of being retried page after page.
     */
    static final class PageOcrException extends Exception {
        private final boolean documentWide;

        PageOcrException(String message, boolean documentWide) {
            super(message);
            this.documentWide = documentWide;
        }

        boolean documentWide() {
            return documentWide;
        }
    }
}
