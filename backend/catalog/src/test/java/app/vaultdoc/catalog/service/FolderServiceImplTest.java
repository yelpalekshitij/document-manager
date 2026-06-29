package app.vaultdoc.catalog.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.vaultdoc.catalog.domain.Folder;
import app.vaultdoc.catalog.domain.FolderRepository;

class FolderServiceImplTest {

    private FolderRepository repo;
    private DefaultFolderSeeder seeder;
    private FolderServiceImpl service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(FolderRepository.class);
        seeder = mock(DefaultFolderSeeder.class);
        service = new FolderServiceImpl(repo, seeder);
        when(repo.save(any(Folder.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void list_seedsBeforeFetching() {
        when(repo.findAllByUserIdOrderBySortOrderAscNameAsc(userId)).thenReturn(List.of());

        service.listForUser(userId);

        verify(seeder, times(1)).ensureSeeded(userId);
    }

    @Test
    void create_rejectsBlankName() {
        assertThatThrownBy(() -> service.create(userId, "", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_topLevel_persists() {
        var saved = service.create(userId, "Tax 2024", null, "#fafafa", "folder");
        ArgumentCaptor<Folder> cap = ArgumentCaptor.forClass(Folder.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getName()).isEqualTo("Tax 2024");
        assertThat(cap.getValue().getUserId()).isEqualTo(userId);
        assertThat(cap.getValue().getParentId()).isNull();
        assertThat(cap.getValue().isSystem()).isFalse();
        assertThat(saved.getColor()).isEqualTo("#fafafa");
    }

    @Test
    void create_underParent_validatesParentExists() {
        UUID parent = UUID.randomUUID();
        when(repo.findByIdAndUserId(parent, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(userId, "Sub", parent, null, null))
                .isInstanceOf(FolderNotFoundException.class);
    }

    @Test
    void update_renamesAndMoves() {
        UUID id = UUID.randomUUID();
        UUID newParent = UUID.randomUUID();
        var existing = new Folder(id, userId, null, "Old", null, null, 0, false);
        when(repo.findByIdAndUserId(id, userId)).thenReturn(Optional.of(existing));
        when(repo.findByIdAndUserId(newParent, userId)).thenReturn(Optional.of(
                new Folder(newParent, userId, null, "Parent", null, null, 0, false)));

        var updated = service.update(userId, id, "New", newParent, "#000", "tag");

        assertThat(updated.getName()).isEqualTo("New");
        assertThat(updated.getParentId()).isEqualTo(newParent);
        assertThat(updated.getColor()).isEqualTo("#000");
        assertThat(updated.getIcon()).isEqualTo("tag");
    }

    @Test
    void update_rejectsSelfParent() {
        UUID id = UUID.randomUUID();
        var existing = new Folder(id, userId, null, "Folder", null, null, 0, false);
        when(repo.findByIdAndUserId(id, userId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(userId, id, null, id, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_returnsTrueWhenRemoved() {
        UUID id = UUID.randomUUID();
        when(repo.deleteByIdAndUserId(id, userId)).thenReturn(1);

        assertThat(service.delete(userId, id)).isTrue();
    }

    @Test
    void delete_returnsFalseWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repo.deleteByIdAndUserId(id, userId)).thenReturn(0);

        assertThat(service.delete(userId, id)).isFalse();
    }
}
