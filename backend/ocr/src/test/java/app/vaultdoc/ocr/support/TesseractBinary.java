package app.vaultdoc.ocr.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    public enum Gate { RUN, SKIP, FAIL }

    /** Env var CI sets so a missing binary fails the build instead of quietly skipping {@code [bin]} tests. */
    public static final String REQUIRE_ENV = "CI_REQUIRE_TESSERACT";

    static Gate gate(boolean available, String requireEnv) {
        if (available) return Gate.RUN;
        return requireEnv != null && requireEnv.strip().equalsIgnoreCase("true") ? Gate.FAIL : Gate.SKIP;
    }

    /**
     * For {@code @BeforeEach} of tests that need the real engine: runs them when
     * tesseract is installed, skips them locally when it isn't, and fails when
     * {@value #REQUIRE_ENV}=true (CI) — so CI can never go green on skipped tests.
     */
    public static void assumeAvailableOrFailInCi() {
        switch (gate(available(), System.getenv(REQUIRE_ENV))) {
            case RUN -> { }
            case SKIP -> assumeTrue(false, "tesseract binary not installed — skipping real-engine test");
            case FAIL -> fail(REQUIRE_ENV + "=true but no tesseract binary was found (PATH, TESSERACT_PATH, "
                    + "/opt/homebrew/bin, /usr/local/bin, /usr/bin)");
        }
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
