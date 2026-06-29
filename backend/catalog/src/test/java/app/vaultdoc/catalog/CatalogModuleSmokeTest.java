package app.vaultdoc.catalog;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Smoke test asserting the Catalog module compiles and JUnit wiring works.
 * Real tests land as features are implemented.
 */
class CatalogModuleSmokeTest {

    @Test
    void moduleIsAlive() {
        assertEquals(2, 1 + 1);
    }
}
