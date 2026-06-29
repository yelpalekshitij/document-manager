package app.vaultdoc.ocr;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Smoke test asserting the Ocr module compiles and JUnit wiring works.
 * Real tests land as features are implemented.
 */
class OcrModuleSmokeTest {

    @Test
    void moduleIsAlive() {
        assertEquals(2, 1 + 1);
    }
}
