package app.vaultdoc.core.auth.password;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Argon2idPasswordHasherTest {

    // Light parameters keep the test suite snappy. Production uses 64 MiB / 3 iter / 4 par.
    private final Argon2idPasswordHasher hasher = new Argon2idPasswordHasher(2, 8 * 1024, 1);

    @Test
    void verifies_a_correct_password() {
        String hash = hasher.hash("correct-horse-battery-staple".toCharArray());
        assertTrue(hasher.verify("correct-horse-battery-staple".toCharArray(), hash));
    }

    @Test
    void rejects_a_wrong_password() {
        String hash = hasher.hash("correct-horse-battery-staple".toCharArray());
        assertFalse(hasher.verify("wrong-password".toCharArray(), hash));
    }

    @Test
    void same_password_produces_different_hashes() {
        String a = hasher.hash("correct-horse-battery-staple".toCharArray());
        String b = hasher.hash("correct-horse-battery-staple".toCharArray());
        assertNotEquals(a, b, "Hash output must be salted — same password must produce different encodings.");
    }

    @Test
    void rejects_empty_input() {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(new char[0]));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(null));
    }

    @Test
    void verify_returns_false_for_garbage_encoding() {
        assertFalse(hasher.verify("anything".toCharArray(), "not-an-argon2-encoding"));
        assertFalse(hasher.verify("anything".toCharArray(), ""));
        assertFalse(hasher.verify("anything".toCharArray(), null));
    }
}
