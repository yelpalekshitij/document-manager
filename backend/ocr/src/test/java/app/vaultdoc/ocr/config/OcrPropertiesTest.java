package app.vaultdoc.ocr.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class OcrPropertiesTest {

    @Test
    void defaults_are_sane() {
        OcrProperties p = OcrProperties.defaults();

        // Phase 1.3 knobs unchanged.
        assertThat(p.consumerGroup()).isEqualTo("vaultdoc-ocr-consumers");
        assertThat(p.batchSize()).isEqualTo(10);
        assertThat(p.pollTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.extractedTextLimitBytes()).isEqualTo(2_000_000);

        // Phase 1.4 Tesseract knobs.
        assertThat(p.languages()).isEqualTo("eng+hin");
        assertThat(p.languageList()).containsExactly("eng", "hin");
        assertThat(p.tesseractPath()).isEqualTo("tesseract");
        assertThat(p.tessdataDir()).isNull();
        assertThat(p.tesseractTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.maxImageBytes()).isEqualTo(25L * 1024 * 1024);
        assertThat(p.maxImagePixels()).isEqualTo(50_000_000L);
        assertThat(p.pdfOcrFallbackEnabled()).isTrue();
        assertThat(p.pdfOcrMaxPages()).isEqualTo(20);
        assertThat(p.pdfOcrDpi()).isEqualTo(300);
        assertThat(p.pdfMinTextCharsPerPage()).isEqualTo(25);
        assertThat(p.tempDir()).isNull();

        // Review fixes: hard bounds on a single document + stranded-entry recovery.
        assertThat(p.maxImageFrames()).isEqualTo(20);
        assertThat(p.pdfMaxPages()).isEqualTo(2_000);
        assertThat(p.documentOcrBudget()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.reclaimMinIdle()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.reclaimInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.maxDeliveries()).isEqualTo(3);
    }

    @Test
    void new_bounds_fall_back_to_defaults_when_out_of_range() {
        OcrProperties p = OcrProperties.defaults()
                .withMaxImageFrames(0)
                .withPdfMaxPages(-1)
                .withDocumentOcrBudget(Duration.ofSeconds(-5))
                .withReclaimMinIdle(Duration.ZERO)
                .withReclaimInterval(null)
                .withMaxDeliveries(0);

        assertThat(p.maxImageFrames()).isEqualTo(20);
        assertThat(p.pdfMaxPages()).isEqualTo(2_000);
        assertThat(p.documentOcrBudget()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.reclaimMinIdle()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.reclaimInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.maxDeliveries()).isEqualTo(3);
        assertThat(p.withMaxDeliveries(1).maxDeliveries()).isEqualTo(1);
    }

    @Test
    void languages_are_parsed_trimmed_and_deduplicated() {
        OcrProperties p = OcrProperties.defaults().withLanguages("  eng + hin,eng  tam ,, hin ");

        assertThat(p.languageList()).containsExactly("eng", "hin", "tam");
    }

    @Test
    void invalid_language_tokens_are_dropped() {
        OcrProperties p = OcrProperties.defaults().withLanguages("eng+-c+../x+eng;rm+chi_sim");

        assertThat(p.languageList()).containsExactly("eng", "chi_sim");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blank_languages_mean_the_default(String languages) {
        // Blank is indistinguishable from "unset" (e.g. OCR_LANGUAGES=) → default eng+hin.
        OcrProperties p = OcrProperties.defaults().withLanguages(languages);

        assertThat(p.languageList()).containsExactly("eng", "hin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+++", "-l", "../../etc"})
    void all_invalid_languages_fall_back_to_eng(String languages) {
        OcrProperties p = OcrProperties.defaults().withLanguages(languages);

        assertThat(p.languageList()).containsExactly("eng");
    }

    @Test
    void out_of_range_values_fall_back_to_defaults() {
        OcrProperties p = OcrProperties.defaults()
                .withPdfOcrDpi(10)
                .withPdfOcrMaxPages(0)
                .withMaxImageBytes(-1)
                .withMaxImagePixels(0)
                .withPdfMinTextCharsPerPage(-5)
                .withTesseractTimeout(Duration.ZERO)
                .withTesseractPath("  ")
                .withTessdataDir(" ")
                .withTempDir("");

        assertThat(p.pdfOcrDpi()).isEqualTo(300);
        assertThat(p.withPdfOcrDpi(601).pdfOcrDpi()).isEqualTo(300);
        assertThat(p.withPdfOcrDpi(72).pdfOcrDpi()).isEqualTo(72);
        assertThat(p.withPdfOcrDpi(600).pdfOcrDpi()).isEqualTo(600);
        assertThat(p.pdfOcrMaxPages()).isEqualTo(20);
        assertThat(p.maxImageBytes()).isEqualTo(25L * 1024 * 1024);
        assertThat(p.maxImagePixels()).isEqualTo(50_000_000L);
        assertThat(p.pdfMinTextCharsPerPage()).isEqualTo(25);
        assertThat(p.tesseractTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.tesseractPath()).isEqualTo("tesseract");
        assertThat(p.tessdataDir()).isNull();
        assertThat(p.tempDir()).isNull();
    }

    @Test
    void fallback_can_be_switched_off_explicitly() {
        assertThat(OcrProperties.defaults().withPdfOcrFallbackEnabled(false).pdfOcrFallbackEnabled()).isFalse();
    }
}
