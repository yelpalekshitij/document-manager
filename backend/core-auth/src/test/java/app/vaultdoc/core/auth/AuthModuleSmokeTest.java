package app.vaultdoc.core.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Smoke test asserting the Auth module compiles and JUnit wiring works.
 * Real tests land as features are implemented.
 */
class AuthModuleSmokeTest {

    @Test
    void moduleIsAlive() {
        assertEquals(2, 1 + 1);
    }
}
