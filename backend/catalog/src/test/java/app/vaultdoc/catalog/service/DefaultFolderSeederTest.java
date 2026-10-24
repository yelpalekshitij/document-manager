package app.vaultdoc.catalog.service;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.Folder;
import app.vaultdoc.catalog.domain.FolderRepository;

class DefaultFolderSeederTest {

    private final FolderRepository folders = mock(FolderRepository.class);
    private final DefaultFolderSeeder seeder = new DefaultFolderSeeder(folders);
    private final UUID userId = UUID.randomUUID();

    @Test
    void seedsEightSystemFoldersOnFirstUse() {
        when(folders.countSystemForUser(userId)).thenReturn(0L);

        seeder.ensureSeeded(userId);

        ArgumentCaptor<Folder> cap = ArgumentCaptor.forClass(Folder.class);
        verify(folders, times(8)).save(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(f -> {
            assertThat(f.isSystem()).isTrue();
            assertThat(f.getUserId()).isEqualTo(userId);
        });
        assertThat(cap.getAllValues()).extracting(Folder::getName)
                .startsWith("Identity", "Financial").endsWith("Travel");
    }

    @Test
    void isIdempotentOnceSeeded() {
        when(folders.countSystemForUser(userId)).thenReturn(8L);

        seeder.ensureSeeded(userId);

        verify(folders, never()).save(any());
    }
}
