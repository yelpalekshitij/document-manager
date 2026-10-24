package app.vaultdoc.ocr.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-job scratch directory for handing document bytes to tesseract. Created
 * owner-only ({@code rwx------} on POSIX — the JDK default for temp dirs),
 * because the files are decrypted user documents. {@link #close()} deletes the
 * whole tree; use with try-with-resources so every exit path cleans up.
 */
final class TempWorkspace implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TempWorkspace.class);

    private final Path dir;

    private TempWorkspace(Path dir) {
        this.dir = dir;
    }

    /** @param root parent directory; null for {@code java.io.tmpdir} */
    static TempWorkspace create(Path root) throws IOException {
        if (root == null) return new TempWorkspace(Files.createTempDirectory("vaultdoc-ocr-"));
        Files.createDirectories(root);
        return new TempWorkspace(Files.createTempDirectory(root, "vaultdoc-ocr-"));
    }

    Path resolve(String name) {
        return dir.resolve(name);
    }

    Path write(String name, byte[] bytes) throws IOException {
        return Files.write(dir.resolve(name), bytes);
    }

    @Override
    public void close() {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete OCR temp file {}: {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean OCR temp dir {}: {}", dir, e.toString());
        }
    }
}
