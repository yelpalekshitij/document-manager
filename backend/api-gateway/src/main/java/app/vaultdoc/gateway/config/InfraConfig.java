package app.vaultdoc.gateway.config;

import java.net.URI;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class InfraConfig {

    @Bean
    ElasticsearchClient elasticsearchClient(@Value("${vaultdoc.elasticsearch.url}") String url) {
        // ES Java client 9.x fluent builder: pass the full URL as the host and use
        // the Jackson 3 mapper (Spring Boot 4's tools.jackson is on the classpath).
        return ElasticsearchClient.of(b -> b
                .host(url)
                .jsonMapper(new Jackson3JsonpMapper()));
    }

    @Bean
    S3Client s3Client(
            @Value("${vaultdoc.s3.endpoint}") String endpoint,
            @Value("${vaultdoc.s3.access-key}") String accessKey,
            @Value("${vaultdoc.s3.secret-key}") String secretKey,
            @Value("${vaultdoc.s3.region:us-east-1}") String region) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                // Path-style access is required for MinIO; harmless for AWS S3.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(
            @Value("${vaultdoc.s3.endpoint}") String endpoint,
            @Value("${vaultdoc.s3.access-key}") String accessKey,
            @Value("${vaultdoc.s3.secret-key}") String secretKey,
            @Value("${vaultdoc.s3.region:us-east-1}") String region) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
