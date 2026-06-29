package app.vaultdoc.core.crypto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Smoke test asserting the Crypto module compiles and JUnit wiring works.
 * Real tests land as features are implemented.
 */
class CryptoModuleSmokeTest {

    @Test
    void moduleIsAlive() {
        assertEquals(2, 1 + 1);
    }
}
