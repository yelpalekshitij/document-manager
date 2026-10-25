package app.vaultdoc.ocr.tesseract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the {@code tesseract} CLI as a child process.
 *
 * <p>Chosen over Tess4J (JNA) — see BUILD_LOG Phase 1.4: a crash, leak or
 * runaway in native Tesseract/Leptonica code stays in the child process
 * instead of taking the JVM down; timeouts are enforceable by killing the
 * process; and it needs no JNA native-access flags on Java 25 or per-platform
 * {@code libtesseract} lookup (macOS arm64 Homebrew vs Debian paths).
 *
 * <p>Process hygiene:
 * <ul>
 *   <li>Arguments are passed as a list — no shell, no interpolation.</li>
 *   <li>stdout/stderr go to files in a private scratch directory, so a chatty
 *       process can never block on a full pipe buffer.</li>
 *   <li>On timeout or interrupt the process <em>and its descendants</em> are
 *       force-killed.</li>
 *   <li>The scratch directory is removed on every path.</li>
 * </ul>
 */
public class CliTesseractRunner implements TesseractRunner {

    private static final Logger log = LoggerFactory.getLogger(CliTesseractRunner.class);
    private static final int STDERR_TAIL_CHARS = 300;

    private final String binary;
    private final Path tessdataDir;
    private final Duration timeout;
    private final Path workRoot;

    /**
     * @param binary      executable name (resolved on PATH) or absolute path
     * @param tessdataDir optional {@code --tessdata-dir}; null for tesseract's default
     * @param timeout     wall-clock budget per invocation
     * @param workRoot    parent for scratch directories; null for {@code java.io.tmpdir}
     */
    public CliTesseractRunner(String binary, Path tessdataDir, Duration timeout, Path workRoot) {
        this.binary = binary;
        this.tessdataDir = tessdataDir;
        this.timeout = timeout;
        this.workRoot = workRoot;
    }

    @Override
    public String recognize(Path image, List<String> languages) {
        List<String> command = new ArrayList<>(List.of(binary, image.toString(), "stdout", "-l", String.join("+", languages)));
        addTessdataDir(command);
        return run(command).stdout();
    }

    @Override
    public Set<String> listLanguages() {
        List<String> command = new ArrayList<>(List.of(binary, "--list-langs"));
        addTessdataDir(command);
        Output output = run(command);
        // Tesseract 5 prints the list on stdout; 4.x used stderr.
        Set<String> languages = parseLanguages(output.stdout());
        return languages.isEmpty() ? parseLanguages(output.stderr()) : languages;
    }

    private void addTessdataDir(List<String> command) {
        if (tessdataDir != null) {
            command.add("--tessdata-dir");
            command.add(tessdataDir.toString());
        }
    }

    private record Output(String stdout, String stderr) {}

    private Output run(List<String> command) {
        Path scratch = createScratch();
        try {
            Path out = scratch.resolve("stdout.txt");
            Path err = scratch.resolve("stderr.txt");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
            // Tesseract's OpenMP threads fight each other (and us) on shared hosts;
            // one thread per process is faster in practice for page-sized inputs.
            builder.environment().put("OMP_THREAD_LIMIT", "1");

            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new TesseractUnavailableException(
                        "tesseract not available at '" + binary + "': " + e.getMessage(), e);
            }

            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    kill(process);
                    throw new TesseractTimeoutException("tesseract timed out after " + human(timeout));
                }
            } catch (InterruptedException e) {
                kill(process);
                Thread.currentThread().interrupt();
                throw new TesseractException("tesseract interrupted", e);
            }

            String stderr = read(err);
            if (process.exitValue() != 0) {
                String exit = "tesseract failed with exit code " + process.exitValue();
                throw new TesseractException(exit + ": " + tail(stderr), exit, null);
            }
            return new Output(read(out), stderr);
        } finally {
            deleteRecursively(scratch);
        }
    }

    private Path createScratch() {
        try {
            if (workRoot == null) return Files.createTempDirectory("vaultdoc-tess-");
            Files.createDirectories(workRoot);
            return Files.createTempDirectory(workRoot, "vaultdoc-tess-");
        } catch (IOException e) {
            throw new TesseractException("could not create tesseract scratch directory: " + e.getMessage(),
                    "could not create tesseract scratch directory", e);
        }
    }

    /** Descendants first — once the parent dies they are re-parented and unreachable. */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("tesseract pid={} did not exit after SIGKILL", process.pid());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static Set<String> parseLanguages(String listing) {
        Set<String> languages = new LinkedHashSet<>();
        for (String line : listing.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("List of available languages")) continue;
            languages.add(trimmed);
        }
        return languages;
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String tail(String stderr) {
        String trimmed = stderr.strip();
        if (trimmed.isEmpty()) return "(no stderr)";
        return trimmed.length() <= STDERR_TAIL_CHARS ? trimmed : "…" + trimmed.substring(trimmed.length() - STDERR_TAIL_CHARS);
    }

    static String human(Duration d) {
        return d.toMillis() < 1000 ? d.toMillis() + "ms" : d.toSeconds() + "s";
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete OCR scratch file {}: {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean OCR scratch dir {}: {}", root, e.toString());
        }
    }
}
