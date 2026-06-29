package app.vaultdoc.ingest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Smoke test asserting the Ingest module compiles and JUnit wiring works.
 * Real tests land as features are implemented.
 */
class IngestModuleSmokeTest {

    @Test
    void moduleIsAlive() {
        assertEquals(2, 1 + 1);
    }
}
