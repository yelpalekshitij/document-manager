package app.vaultdoc.inbox;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Smoke test asserting the Inbox module compiles and JUnit wiring works.
 * Real tests land as features are implemented.
 */
class InboxModuleSmokeTest {

    @Test
    void moduleIsAlive() {
        assertEquals(2, 1 + 1);
    }
}
