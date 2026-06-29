package app.vaultdoc.gateway.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that touch the documents pipeline.
 * Spins up MinIO + Elasticsearch alongside the Postgres + Redis provided by
 * {@link TestcontainersConfig}.
 *
 * <p>Containers are static so they are started once and reused across the
 * suite.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RecordingEmailSender.AsBean.class})
@Testcontainers
public abstract class DocumentITBase {

    private static final String MINIO_USER = "vaultdoc";
    private static final String MINIO_PASSWORD = "vaultdoc_dev_pw";
    private static final String BUCKET = "vaultdoc-docs";

    protected static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("minio/minio:RELEASE.2025-01-20T14-49-07Z"))
            .withUserName(MINIO_USER)
            .withPassword(MINIO_PASSWORD);

    protected static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.2.8"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    static {
        MINIO.start();
        ELASTICSEARCH.start();
        ensureBucket();
    }

    @DynamicPropertySource
    static void overrides(DynamicPropertyRegistry registry) {
        registry.add("vaultdoc.s3.endpoint", MINIO::getS3URL);
        registry.add("vaultdoc.s3.access-key", () -> MINIO_USER);
        registry.add("vaultdoc.s3.secret-key", () -> MINIO_PASSWORD);
        registry.add("vaultdoc.s3.bucket", () -> BUCKET);
        registry.add("vaultdoc.s3.region", () -> "us-east-1");
        registry.add("vaultdoc.elasticsearch.url", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
        // Force ES to make writes visible before the call returns — ITs assert immediately.
        registry.add("vaultdoc.search.refresh-policy", () -> "true");
    }

    private static void ensureBucket() {
        try (var s3 = software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(java.net.URI.create(MINIO.getS3URL()))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(MINIO_USER, MINIO_PASSWORD)))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(true).build())
                .build()) {
            try {
                s3.headBucket(b -> b.bucket(BUCKET));
            } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
                s3.createBucket(b -> b.bucket(BUCKET));
            }
        }
    }
}
