package app.vaultdoc.ocr.support;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

import app.vaultdoc.ocr.support.TesseractBinary.Gate;

/** CI sets {@code CI_REQUIRE_TESSERACT=true} so a missing binary fails the build instead of skipping {@code [bin]} tests. */
class TesseractBinaryTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "true,  null,  RUN",
            "true,  true,  RUN",
            "false, null,  SKIP",
            "false, false, SKIP",
            "false, '',    SKIP",
            "false, true,  FAIL",
            "false, TRUE,  FAIL",
            "false, ' true ', FAIL",
    })
    void gate_fails_instead_of_skipping_when_required(boolean available, String requireEnv, Gate expected) {
        assertThat(TesseractBinary.gate(available, requireEnv)).isEqualTo(expected);
    }
}
