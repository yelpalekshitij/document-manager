package app.vaultdoc.ocr.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Locates a real {@code tesseract} for the tests that genuinely need one.
 * Order: {@code TESSERACT_PATH} env, PATH, then the usual Homebrew / Debian
 * locations (Gradle daemons don't always inherit an interactive PATH).
 */
public final class TesseractBinary {

    private static final Optional<String> FOUND = locate();

    private TesseractBinary() {}

    public static Optional<String> path() {
        return FOUND;
    }

    public static boolean available() {
        return FOUND.isPresent();
    }

    private static Optional<String> locate() {
        List<String> candidates = new ArrayList<>();
        String env = System.getenv("TESSERACT_PATH");
        if (env != null && !env.isBlank()) candidates.add(env);
        candidates.add("tesseract");
        candidates.addAll(List.of("/opt/homebrew/bin/tesseract", "/usr/local/bin/tesseract", "/usr/bin/tesseract"));
        for (String candidate : candidates) {
            if (candidate.startsWith("/") && !Files.isExecutable(Path.of(candidate))) continue;
            if (runs(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static boolean runs(String binary) {
        try {
            Process p = new ProcessBuilder(binary, "--version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
