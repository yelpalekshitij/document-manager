package app.vaultdoc.catalog.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.vaultdoc.catalog.domain.DocumentRepository;
import app.vaultdoc.catalog.domain.DocumentTagRepository;
import app.vaultdoc.catalog.domain.FolderRepository;
import app.vaultdoc.catalog.domain.TagRepository;
import app.vaultdoc.catalog.service.DefaultFolderSeeder;
import app.vaultdoc.catalog.service.DocumentService;
import app.vaultdoc.catalog.service.DocumentServiceImpl;
import app.vaultdoc.catalog.service.FolderService;
import app.vaultdoc.catalog.service.FolderServiceImpl;
import app.vaultdoc.catalog.service.TagService;
import app.vaultdoc.catalog.service.TagServiceImpl;

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

    @Bean
    @ConditionalOnMissingBean
    TagService tagService(
            TagRepository tags,
            DocumentTagRepository documentTags,
            DocumentRepository documents,
            ApplicationEventPublisher events) {
        return new TagServiceImpl(tags, documentTags, documents, events);
    }
}
