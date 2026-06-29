package app.vaultdoc.core.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Smoke test asserting the Storage module compiles and JUnit wiring works.
 * Real tests land as features are implemented.
 */
class StorageModuleSmokeTest {

    @Test
    void moduleIsAlive() {
        assertEquals(2, 1 + 1);
    }
}
