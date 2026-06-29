package app.vaultdoc.core.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObjectStorage objectStorage(
            S3Client s3,
            S3Presigner presigner,
            @Value("${vaultdoc.s3.bucket}") String bucket) {
        return new S3ObjectStorage(s3, presigner, bucket);
    }
}
