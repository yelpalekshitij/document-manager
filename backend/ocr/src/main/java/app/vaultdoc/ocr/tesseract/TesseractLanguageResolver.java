package app.vaultdoc.ocr.tesseract;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intersects the configured OCR languages (default {@code eng+hin}) with the
 * language packs actually installed for tesseract.
 *
 * <p>A missing pack degrades gracefully: we log one clear warning naming the
 * pack and carry on with what is installed, rather than failing every
 * document. Only when <em>none</em> of the configured languages is installed
 * does {@link #resolve()} come back empty — callers turn that into
 * {@code OcrStatus.FAILED}.
 *
 * <p>A successful resolution is cached for the life of the process (installing
 * a pack needs a restart). A failed listing — typically "binary not found" — is
 * not cached, so the next document retries and the failure surfaces per
 * document instead of being remembered silently.
 */
public class TesseractLanguageResolver {

    private static final Logger log = LoggerFactory.getLogger(TesseractLanguageResolver.class);

    private final TesseractRunner runner;
    private final List<String> configured;
    private final Consumer<String> warn;
    private volatile List<String> resolved;

    public TesseractLanguageResolver(TesseractRunner runner, List<String> configured) {
        this(runner, configured, log::warn);
    }

    public TesseractLanguageResolver(TesseractRunner runner, List<String> configured, Consumer<String> warn) {
        this.runner = runner;
        this.configured = List.copyOf(configured);
        this.warn = warn;
    }

    /** The configured languages, in priority order, before checking what is installed. */
    public List<String> configured() {
        return configured;
    }

    /**
     * Configured languages that are installed, in configured order.
     *
     * @throws TesseractException when the installed packs cannot be listed
     */
    public List<String> resolve() {
        List<String> cached = resolved;
        if (cached != null) return cached;
        synchronized (this) {
            if (resolved != null) return resolved;
            Set<String> installed = runner.listLanguages();
            List<String> usable = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String language : configured) {
                (installed.contains(language) ? usable : missing).add(language);
            }
            if (!missing.isEmpty()) {
                warn.accept(("OCR language pack(s) %s not installed for tesseract (installed: %s); "
                        + "continuing with %s. Install the traineddata to enable them.")
                        .formatted(missing, installed, usable.isEmpty() ? "NO languages" : usable));
            }
            resolved = List.copyOf(usable);
            return resolved;
        }
    }
}
