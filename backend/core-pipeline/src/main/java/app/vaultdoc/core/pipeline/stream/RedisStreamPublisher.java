package app.vaultdoc.core.pipeline.stream;

import java.util.Map;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisStreamPublisher implements StreamPublisher {

    private final StringRedisTemplate redis;

    public RedisStreamPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String publish(String stream, Map<String, String> entry) {
        MapRecord<String, String, String> record = MapRecord.create(stream, entry);
        RecordId id = redis.opsForStream().add(record);
        return id == null ? null : id.getValue();
    }
}
