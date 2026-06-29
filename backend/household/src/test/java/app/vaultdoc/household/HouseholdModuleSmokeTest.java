package app.vaultdoc.household;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Smoke test asserting the Household module compiles and JUnit wiring works.
 * Real tests land as features are implemented.
 */
class HouseholdModuleSmokeTest {

    @Test
    void moduleIsAlive() {
        assertEquals(2, 1 + 1);
    }
}
