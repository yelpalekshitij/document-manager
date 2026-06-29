package app.vaultdoc.core.auth.twofa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisFirstFactorCache implements FirstFactorCache {

    private static final String NS = "2fa:ff:";
    private static final int RAW_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    private final StringRedisTemplate redis;

    public RedisFirstFactorCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String store(UUID userId, Duration ttl) {
        byte[] raw = new byte[RAW_BYTES];
        RNG.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String key = keyFor(token);
        redis.opsForValue().set(key, userId.toString(), ttl);
        return token;
    }

    @Override
    public Optional<UUID> consume(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        String key = keyFor(rawToken);
        String value = redis.execute((RedisCallback<String>) connection -> {
            byte[] raw = connection.stringCommands().getDel(key.getBytes());
            return raw == null ? null : new String(raw);
        });
        if (value == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String keyFor(String token) {
        // Store by SHA-256 of the token — same pattern as VerificationToken
        // and ChallengeCache. A Redis dump leaks no usable raw tokens.
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return NS + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
