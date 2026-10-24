package app.vaultdoc.ocr.tesseract;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Seam between the OCR services and the Tesseract engine. The production impl
 * shells out to the CLI ({@link CliTesseractRunner}); tests plug in fakes so
 * routing, failure surfacing, temp cleanup and the scanned-PDF fallback are
 * all testable without the binary.
 */
public interface TesseractRunner {

    /**
     * OCR a single image file. Implementations enforce their own timeout.
     *
     * @throws TesseractTimeoutException     the call ran past its budget (process killed)
     * @throws TesseractUnavailableException the engine is not installed
     * @throws TesseractException            any other engine failure
     */
    String recognize(Path image, List<String> languages);

    /** Language packs installed for this engine (e.g. {@code eng}, {@code hin}, {@code osd}). */
    Set<String> listLanguages();
}
