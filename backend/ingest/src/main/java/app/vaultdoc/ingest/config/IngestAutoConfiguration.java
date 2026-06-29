package app.vaultdoc.ingest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.vaultdoc.catalog.service.DocumentService;
import app.vaultdoc.core.storage.ObjectStorage;
import app.vaultdoc.core.storage.StorageProperties;
import app.vaultdoc.ingest.service.IngestService;
import app.vaultdoc.ingest.service.IngestServiceImpl;
import app.vaultdoc.ingest.service.MimeAllowlist;
import app.vaultdoc.ingest.service.NoopVirusScanner;
import app.vaultdoc.ingest.service.VirusScanner;
import app.vaultdoc.search.service.SearchService;

@Configuration
@EnableConfigurationProperties(IngestProperties.class)
public class IngestAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    VirusScanner virusScanner() {
        return new NoopVirusScanner();
    }

    @Bean
    @ConditionalOnMissingBean
    MimeAllowlist mimeAllowlist(IngestProperties properties) {
        return new MimeAllowlist(properties.allowedMimeTypes());
    }

    @Bean
    @ConditionalOnMissingBean
    IngestService ingestService(
            DocumentService documents,
            ObjectStorage storage,
            SearchService search,
            VirusScanner scanner,
            MimeAllowlist allowlist,
            ApplicationEventPublisher eventPublisher,
            IngestProperties ingestProperties,
            StorageProperties storageProperties) {
        return new IngestServiceImpl(
                documents,
                storage,
                search,
                scanner,
                allowlist,
                eventPublisher,
                ingestProperties.maxSizeBytes(),
                storageProperties.presignPutTtl());
    }
}
