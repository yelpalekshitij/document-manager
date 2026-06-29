package app.vaultdoc.core.auth.passkey;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisChallengeCache implements ChallengeCache {

    private static final String NS = "passkey:challenge:";

    private final StringRedisTemplate redis;

    public RedisChallengeCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public UUID store(Flow flow, byte[] challenge, UUID userId, Duration ttl) {
        UUID challengeId = UUID.randomUUID();
        String key = keyFor(flow, challengeId);
        redis.opsForValue().set(key, encode(challenge, userId), ttl);
        return challengeId;
    }

    @Override
    public Optional<StoredChallenge> consume(Flow flow, UUID challengeId) {
        String key = keyFor(flow, challengeId);
        // GETDEL is the atomic read-and-remove operation Redis 6.2+ provides.
        String value = redis.execute((RedisCallback<String>) connection -> {
            byte[] raw = connection.stringCommands().getDel(key.getBytes());
            return raw == null ? null : new String(raw);
        });
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(decode(value));
    }

    private static String keyFor(Flow flow, UUID id) {
        return NS + flow.name().toLowerCase() + ":" + id;
    }

    private static String encode(byte[] challenge, UUID userId) {
        ByteBuffer buf = ByteBuffer.allocate(16 + challenge.length);
        if (userId != null) {
            buf.putLong(userId.getMostSignificantBits()).putLong(userId.getLeastSignificantBits());
        } else {
            buf.putLong(0L).putLong(0L);
        }
        buf.put(challenge);
        return java.util.Base64.getEncoder().encodeToString(buf.array());
    }

    private static StoredChallenge decode(String value) {
        byte[] raw = java.util.Base64.getDecoder().decode(value);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        long msb = buf.getLong();
        long lsb = buf.getLong();
        UUID userId = (msb == 0L && lsb == 0L) ? null : new UUID(msb, lsb);
        byte[] challenge = new byte[buf.remaining()];
        buf.get(challenge);
        return new StoredChallenge(challenge, userId);
    }
}
