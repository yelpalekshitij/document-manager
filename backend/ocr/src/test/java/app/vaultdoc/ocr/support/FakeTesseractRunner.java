package app.vaultdoc.ocr.support;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import javax.imageio.ImageIO;

import app.vaultdoc.ocr.tesseract.TesseractRunner;

/**
 * In-memory stand-in for the tesseract CLI. Records every call — including
 * whether the input file existed while the "engine" was running and, for
 * images ImageIO can read, the pixel size — so tests can assert on what the
 * services hand to the engine without the binary being installed.
 */
public class FakeTesseractRunner implements TesseractRunner {

    public record Call(Path image, List<String> languages, boolean existed, long sizeBytes, int width, int height) {}

    private final BiFunction<Path, List<String>, String> behaviour;
    private volatile Set<String> installed = Set.of("eng", "osd");
    private volatile RuntimeException listFailure;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger listCalls = new AtomicInteger();

    private FakeTesseractRunner(BiFunction<Path, List<String>, String> behaviour) {
        this.behaviour = behaviour;
    }

    public static FakeTesseractRunner returning(String text) {
        return new FakeTesseractRunner((path, langs) -> text);
    }

    public static FakeTesseractRunner throwing(RuntimeException failure) {
        return new FakeTesseractRunner((path, langs) -> { throw failure; });
    }

    public static FakeTesseractRunner answering(BiFunction<Path, List<String>, String> behaviour) {
        return new FakeTesseractRunner(behaviour);
    }

    public FakeTesseractRunner withInstalled(String... languages) {
        this.installed = Set.of(languages);
        return this;
    }

    public FakeTesseractRunner withListFailure(RuntimeException failure) {
        this.listFailure = failure;
        return this;
    }

    @Override
    public String recognize(Path image, List<String> languages) {
        boolean existed = Files.exists(image);
        long size = -1;
        int width = -1;
        int height = -1;
        if (existed) {
            try {
                size = Files.size(image);
                BufferedImage read = ImageIO.read(image.toFile());
                if (read != null) {
                    width = read.getWidth();
                    height = read.getHeight();
                }
            } catch (IOException ignored) {
                // Not every fixture is a decodable image; size is enough then.
            }
        }
        calls.add(new Call(image, List.copyOf(languages), existed, size, width, height));
        return behaviour.apply(image, languages);
    }

    @Override
    public Set<String> listLanguages() {
        listCalls.incrementAndGet();
        if (listFailure != null) throw listFailure;
        return installed;
    }

    public List<Call> calls() {
        return calls;
    }

    public int listCalls() {
        return listCalls.get();
    }
}
