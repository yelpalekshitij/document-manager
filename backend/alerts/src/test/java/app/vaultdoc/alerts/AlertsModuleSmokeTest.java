package app.vaultdoc.alerts;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Smoke test asserting the Alerts module compiles and JUnit wiring works.
 * Real tests land as features are implemented.
 */
class AlertsModuleSmokeTest {

    @Test
    void moduleIsAlive() {
        assertEquals(2, 1 + 1);
    }
}
