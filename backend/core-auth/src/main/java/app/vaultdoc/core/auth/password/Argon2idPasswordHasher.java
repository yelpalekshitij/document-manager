package app.vaultdoc.core.auth.password;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;

/**
 * Argon2id implementation following OWASP 2024 baseline parameters:
 * memory cost = 64 MiB, time cost = 3, parallelism = 4.
 *
 * <p>The encoded hash is self-describing — algorithm, version, and parameters are
 * part of the output, so verification automatically uses the same params that
 * were active at hash time. Bumping the params for new hashes is therefore safe
 * and backward-compatible.
 */
public class Argon2idPasswordHasher implements PasswordHasher {

    private static final int MEMORY_KIB = 64 * 1024;
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 4;

    private final Argon2 argon2;
    private final int iterations;
    private final int memory;
    private final int parallelism;

    public Argon2idPasswordHasher() {
        this(ITERATIONS, MEMORY_KIB, PARALLELISM);
    }

    Argon2idPasswordHasher(int iterations, int memoryKib, int parallelism) {
        this.argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
        this.iterations = iterations;
        this.memory = memoryKib;
        this.parallelism = parallelism;
    }

    @Override
    public String hash(char[] plaintext) {
        if (plaintext == null || plaintext.length == 0) {
            throw new IllegalArgumentException("plaintext must not be empty");
        }
        try {
            return argon2.hash(iterations, memory, parallelism, plaintext);
        } finally {
            argon2.wipeArray(plaintext);
        }
    }

    @Override
    public boolean verify(char[] plaintext, String encoded) {
        if (plaintext == null || encoded == null || encoded.isEmpty()) {
            return false;
        }
        try {
            return argon2.verify(encoded, plaintext);
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            argon2.wipeArray(plaintext);
        }
    }
}
