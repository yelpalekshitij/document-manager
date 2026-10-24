package app.vaultdoc.ocr.tesseract;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.vaultdoc.ocr.support.FakeTesseractRunner;

class TesseractLanguageResolverTest {

    /** Warning sink — production wires it to the SLF4J logger at WARN. */
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @Test
    void missing_language_degrades_with_warning() {
        var runner = FakeTesseractRunner.returning("").withInstalled("eng", "osd");
        var resolver = new TesseractLanguageResolver(runner, List.of("eng", "hin"), warnings::add);

        assertThat(resolver.resolve()).containsExactly("eng");
        assertThat(warnings()).singleElement().satisfies(msg -> {
            assertThat(msg).contains("hin");
            assertThat(msg).contains("eng");
        });
    }

    @Test
    void all_installed_languages_are_kept() {
        var runner = FakeTesseractRunner.returning("").withInstalled("eng", "hin", "osd");
        var resolver = new TesseractLanguageResolver(runner, List.of("hin", "eng"), warnings::add);

        assertThat(resolver.resolve()).containsExactly("hin", "eng");
        assertThat(warnings()).isEmpty();
    }

    @Test
    void none_installed_returns_empty() {
        var runner = FakeTesseractRunner.returning("").withInstalled("osd");
        var resolver = new TesseractLanguageResolver(runner, List.of("eng", "hin"), warnings::add);

        assertThat(resolver.resolve()).isEmpty();
        assertThat(warnings()).isNotEmpty();
    }

    @Test
    void listing_failure_is_not_cached() {
        var runner = FakeTesseractRunner.returning("")
                .withListFailure(new TesseractUnavailableException("tesseract not found", null));
        var resolver = new TesseractLanguageResolver(runner, List.of("eng"), warnings::add);

        assertThatThrownBy(resolver::resolve).isInstanceOf(TesseractUnavailableException.class);
        assertThatThrownBy(resolver::resolve).isInstanceOf(TesseractUnavailableException.class);
        assertThat(runner.listCalls()).isEqualTo(2);
    }

    @Test
    void successful_resolution_is_cached() {
        var runner = FakeTesseractRunner.returning("").withInstalled("eng");
        var resolver = new TesseractLanguageResolver(runner, List.of("eng", "hin"), warnings::add);

        resolver.resolve();
        resolver.resolve();
        resolver.resolve();

        assertThat(runner.listCalls()).isEqualTo(1);
        // One warning at resolution time, not one per document.
        assertThat(warnings()).hasSize(1);
    }

    @Test
    void language_match_is_case_sensitive() {
        var runner = FakeTesseractRunner.returning("").withInstalled("eng");
        var resolver = new TesseractLanguageResolver(runner, List.of("ENG"), warnings::add);

        assertThat(resolver.resolve()).isEmpty();
        assertThat(resolver.configured()).containsExactly("ENG");
    }

    private List<String> warnings() {
        return warnings;
    }
}
