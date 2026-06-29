package app.vaultdoc.catalog.service;

import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import app.vaultdoc.catalog.domain.Folder;
import app.vaultdoc.catalog.domain.FolderRepository;

@Transactional
public class FolderServiceImpl implements FolderService {

    private final FolderRepository folders;
    private final DefaultFolderSeeder seeder;

    public FolderServiceImpl(FolderRepository folders, DefaultFolderSeeder seeder) {
        this.folders = folders;
        this.seeder = seeder;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Folder> listForUser(UUID userId) {
        seeder.ensureSeeded(userId);
        return folders.findAllByUserIdOrderBySortOrderAscNameAsc(userId);
    }

    @Override
    public Folder create(UUID userId, String name, UUID parentId, String color, String icon) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Folder name must not be blank");
        }
        if (parentId != null) {
            folders.findByIdAndUserId(parentId, userId)
                    .orElseThrow(() -> new FolderNotFoundException(parentId));
        }
        var folder = new Folder(null, userId, parentId, name.trim(), color, icon, 0, false);
        return folders.save(folder);
    }

    @Override
    public Folder update(UUID userId, UUID folderId, String newName, UUID newParentId,
                         String color, String icon) {
        var folder = folders.findByIdAndUserId(folderId, userId)
                .orElseThrow(() -> new FolderNotFoundException(folderId));
        if (newName != null && !newName.isBlank()) {
            folder.rename(newName.trim());
        }
        if (newParentId != null) {
            if (newParentId.equals(folderId)) {
                throw new IllegalArgumentException("A folder cannot be its own parent");
            }
            folders.findByIdAndUserId(newParentId, userId)
                    .orElseThrow(() -> new FolderNotFoundException(newParentId));
            folder.moveTo(newParentId);
        }
        if (color != null) folder.setColor(color.isBlank() ? null : color);
        if (icon != null) folder.setIcon(icon.isBlank() ? null : icon);
        return folders.save(folder);
    }

    @Override
    public boolean delete(UUID userId, UUID folderId) {
        return folders.deleteByIdAndUserId(folderId, userId) > 0;
    }
}
