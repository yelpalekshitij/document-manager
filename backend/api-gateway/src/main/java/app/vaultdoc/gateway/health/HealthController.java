package app.vaultdoc.gateway.health;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final DataSource dataSource;
    private final RedisConnectionFactory redis;
    private final ElasticsearchClient elasticsearch;
    private final S3Client s3;
    private final String s3Bucket;

    public HealthController(
            DataSource dataSource,
            RedisConnectionFactory redis,
            ElasticsearchClient elasticsearch,
            S3Client s3,
            @Value("${vaultdoc.s3.bucket}") String s3Bucket) {
        this.dataSource = dataSource;
        this.redis = redis;
        this.elasticsearch = elasticsearch;
        this.s3 = s3;
        this.s3Bucket = s3Bucket;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        var components = new LinkedHashMap<String, String>();
        components.put("db", probe(this::pingDb));
        components.put("redis", probe(this::pingRedis));
        components.put("elasticsearch", probe(this::pingElasticsearch));
        components.put("s3", probe(this::pingS3));

        boolean allUp = components.values().stream().allMatch("UP"::equals);
        String overall = allUp ? "UP" : "DEGRADED";

        Map<String, Object> body = Map.of(
                "status", overall,
                "components", components);
        return ResponseEntity.ok(body);
    }

    private String probe(Runnable check) {
        try {
            check.run();
            return "UP";
        } catch (RuntimeException e) {
            return "DOWN";
        }
    }

    private void pingDb() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT 1")) {
            stmt.execute();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void pingRedis() {
        try (var conn = redis.getConnection()) {
            conn.ping();
        }
    }

    private void pingElasticsearch() {
        try {
            elasticsearch.ping();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void pingS3() {
        s3.headBucket(HeadBucketRequest.builder().bucket(s3Bucket).build());
    }
}
