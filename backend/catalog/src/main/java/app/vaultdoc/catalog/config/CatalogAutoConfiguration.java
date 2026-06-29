package app.vaultdoc.catalog.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.vaultdoc.catalog.domain.DocumentRepository;
import app.vaultdoc.catalog.domain.FolderRepository;
import app.vaultdoc.catalog.service.DefaultFolderSeeder;
import app.vaultdoc.catalog.service.DocumentService;
import app.vaultdoc.catalog.service.DocumentServiceImpl;
import app.vaultdoc.catalog.service.FolderService;
import app.vaultdoc.catalog.service.FolderServiceImpl;

@Configuration
public class CatalogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    DefaultFolderSeeder defaultFolderSeeder(FolderRepository folders) {
        return new DefaultFolderSeeder(folders);
    }

    @Bean
    @ConditionalOnMissingBean
    FolderService folderService(FolderRepository folders, DefaultFolderSeeder seeder) {
        return new FolderServiceImpl(folders, seeder);
    }

    @Bean
    @ConditionalOnMissingBean
    DocumentService documentService(
            DocumentRepository documents,
            FolderRepository folders,
            DefaultFolderSeeder seeder) {
        return new DocumentServiceImpl(documents, folders, seeder);
    }
}
