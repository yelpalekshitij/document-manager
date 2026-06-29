package app.vaultdoc.core.auth.twofa;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encrypt/decrypt for TOTP secrets stored in the database.
 *
 * <p>Storage format: {@code BASE64(iv) || "." || BASE64(ciphertext+tag)}.
 * The IV is 12 random bytes; GCM tag is 128 bits (appended by JCA to the ciphertext).
 *
 * <p>Phase 3 (Zero-Knowledge mode) will swap the server key for a user-derived
 * KEK. The interface — encrypt/decrypt a String — stays the same.
 */
public class TotpSecretEncryptor {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKey key;

    public TotpSecretEncryptor(String rawKey) {
        byte[] keyBytes = rawKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // Pad with zeros to reach 32 bytes (256 bits). For dev only.
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            keyBytes = padded;
        } else if (keyBytes.length > 32) {
            byte[] truncated = new byte[32];
            System.arraycopy(keyBytes, 0, truncated, 0, 32);
            keyBytes = truncated;
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv)
                    + "." + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP secret encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        try {
            String[] parts = stored.split("\\.", 2);
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP secret decryption failed", e);
        }
    }
}
