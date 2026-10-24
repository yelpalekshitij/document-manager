package app.vaultdoc.catalog.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import app.vaultdoc.catalog.domain.DocumentRepository;
import app.vaultdoc.catalog.domain.DocumentTagRepository;
import app.vaultdoc.catalog.domain.FolderRepository;
import app.vaultdoc.catalog.domain.TagRepository;
import app.vaultdoc.catalog.service.DocumentServiceImpl;
import app.vaultdoc.catalog.service.FolderServiceImpl;
import app.vaultdoc.catalog.service.TagServiceImpl;

class CatalogAutoConfigurationTest {

    private final CatalogAutoConfiguration config = new CatalogAutoConfiguration();

    @Test
    void wiresEveryCatalogService() {
        var folders = mock(FolderRepository.class);
        var documents = mock(DocumentRepository.class);
        var seeder = config.defaultFolderSeeder(folders);

        assertThat(config.folderService(folders, seeder)).isInstanceOf(FolderServiceImpl.class);
        assertThat(config.documentService(documents, folders, seeder)).isInstanceOf(DocumentServiceImpl.class);
        assertThat(config.tagService(
                mock(TagRepository.class),
                mock(DocumentTagRepository.class),
                documents,
                mock(ApplicationEventPublisher.class))).isInstanceOf(TagServiceImpl.class);
    }
}
