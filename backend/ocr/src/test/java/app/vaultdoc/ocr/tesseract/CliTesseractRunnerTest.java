package app.vaultdoc.ocr.tesseract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the real {@link ProcessBuilder} path against fake tesseract
 * executables written as POSIX shell scripts — so process handling (args,
 * exit codes, timeouts, kill, stdout capture, scratch cleanup) is covered
 * without the real binary.
 */
@DisabledOnOs(OS.WINDOWS)
class CliTesseractRunnerTest {

    @TempDir Path dir;
    private Path work;
    private Path image;

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(dir.resolve("work"));
        image = Files.writeString(dir.resolve("page.png"), "fake image");
    }

    @Test
    void returns_stdout_text() throws IOException {
        Path bin = script("printf 'Hello तुम\\f'");
        var runner = new CliTesseractRunner(bin.toString(), null, Duration.ofSeconds(10), work);

        assertThat(runner.recognize(image, List.of("eng"))).isEqualTo("Hello तुम\f");
    }

    @Test
    void passes_languages_and_tessdata_dir_as_separate_args() throws IOException {
        Path argsFile = dir.resolve("args.txt");
        Path bin = script("for a in \"$@\"; do echo \"$a\" >> '" + argsFile + "'; done; echo ok");
        Path tessdata = Files.createDirectories(dir.resolve("tess data"));
        var runner = new CliTesseractRunner(bin.toString(), tessdata, Duration.ofSeconds(10), work);

        runner.recognize(image, List.of("eng", "hin"));

        assertThat(Files.readAllLines(argsFile)).containsExactly(
                image.toString(), "stdout", "-l", "eng+hin", "--tessdata-dir", tessdata.toString());
    }

    @Test
    void non_zero_exit_raises_with_stderr() throws IOException {
        Path bin = script("echo 'Error in pixReadStream: Unknown format' >&2; exit 1");
        var runner = new CliTesseractRunner(bin.toString(), null, Duration.ofSeconds(10), work);

        assertThatThrownBy(() -> runner.recognize(image, List.of("eng")))
                .isExactlyInstanceOf(TesseractException.class)
                .hasMessageContaining("exit code 1")
                .hasMessageContaining("Unknown format");
    }

    @Test
    void timeout_kills_process_and_raises() throws IOException {
        Path pidFile = dir.resolve("pid");
        Path childPidFile = dir.resolve("child-pid");
        // The shell records its pid and its child's pid, then waits — both must die.
        Path bin = script("echo $$ > '" + pidFile + "'; sleep 30 & echo $! > '" + childPidFile + "'; wait; echo late");
        var runner = new CliTesseractRunner(bin.toString(), null, Duration.ofSeconds(3), work);

        long started = System.nanoTime();
        assertThatThrownBy(() -> runner.recognize(image, List.of("eng")))
                .isInstanceOf(TesseractTimeoutException.class)
                .hasMessageContaining("timed out after 3s");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));

        long pid = Long.parseLong(Files.readString(pidFile).strip());
        long childPid = Long.parseLong(Files.readString(childPidFile).strip());
        await().atMost(Duration.ofSeconds(5)).until(() -> ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true));
        await().atMost(Duration.ofSeconds(5)).until(() -> ProcessHandle.of(childPid).map(h -> !h.isAlive()).orElse(true));
    }

    @Test
    void missing_binary_raises_unavailable() {
        var runner = new CliTesseractRunner(dir.resolve("no-such-tesseract").toString(), null,
                Duration.ofSeconds(5), work);

        assertThatThrownBy(() -> runner.recognize(image, List.of("eng")))
                .isInstanceOf(TesseractUnavailableException.class)
                .hasMessageContaining("no-such-tesseract");
        assertThatThrownBy(runner::listLanguages).isInstanceOf(TesseractUnavailableException.class);
    }

    @Test
    void large_output_does_not_deadlock() throws IOException {
        // ~1 MB on stdout and stderr — far past a 64 KB pipe buffer.
        Path bin = script("i=0; while [ $i -lt 16 ]; do head -c 65536 /dev/zero | tr '\\0' 'a'; "
                + "head -c 65536 /dev/zero | tr '\\0' 'e' >&2; i=$((i+1)); done");
        var runner = new CliTesseractRunner(bin.toString(), null, Duration.ofSeconds(20), work);

        assertThat(runner.recognize(image, List.of("eng"))).hasSize(16 * 65536);
    }

    @Test
    void lists_languages_skipping_header() throws IOException {
        Path bin = script("""
                if [ "$1" = "--list-langs" ]; then
                  echo 'List of available languages in "/opt/tessdata/" (3):'
                  echo 'eng'
                  echo ''
                  echo 'hin '
                  echo 'osd'
                fi""");
        var runner = new CliTesseractRunner(bin.toString(), null, Duration.ofSeconds(10), work);

        assertThat(runner.listLanguages()).containsExactlyInAnyOrder("eng", "hin", "osd");
    }

    @Test
    void removes_its_scratch_files() throws IOException {
        var ok = new CliTesseractRunner(script("echo fine").toString(), null, Duration.ofSeconds(10), work);
        var bad = new CliTesseractRunner(script("exit 3").toString(), null, Duration.ofSeconds(10), work);
        var slow = new CliTesseractRunner(script("sleep 30").toString(), null, Duration.ofMillis(300), work);

        ok.recognize(image, List.of("eng"));
        assertThatThrownBy(() -> bad.recognize(image, List.of("eng"))).isInstanceOf(TesseractException.class);
        assertThatThrownBy(() -> slow.recognize(image, List.of("eng"))).isInstanceOf(TesseractTimeoutException.class);

        try (Stream<Path> left = Files.list(work)) {
            assertThat(left).isEmpty();
        }
        // The caller's input is not ours to delete.
        assertThat(image).exists();
    }

    @Test
    void interrupt_kills_process() throws Exception {
        Path pidFile = dir.resolve("pid");
        Path bin = script("echo $$ > '" + pidFile + "'; sleep 30");
        var runner = new CliTesseractRunner(bin.toString(), null, Duration.ofSeconds(30), work);

        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                runner.recognize(image, List.of("eng"));
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.complete(Thread.currentThread().isInterrupted() ? t : new AssertionError("interrupt flag lost", t));
            }
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(pidFile) && Files.size(pidFile) > 0);
        worker.interrupt();

        Throwable thrown = outcome.get(10, TimeUnit.SECONDS);
        assertThat(thrown).isInstanceOf(TesseractException.class).hasMessageContaining("interrupted");
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        await().atMost(Duration.ofSeconds(5)).until(() -> ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true));
    }

    private Path script(String body) throws IOException {
        Path bin = Files.createTempFile(dir, "fake-tesseract-", ".sh");
        Files.writeString(bin, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwx------"));
        return bin;
    }
}
