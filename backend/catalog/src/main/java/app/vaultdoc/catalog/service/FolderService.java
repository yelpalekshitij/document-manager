package app.vaultdoc.catalog.service;

import java.util.List;
import java.util.UUID;

import app.vaultdoc.catalog.domain.Folder;

public interface FolderService {

    List<Folder> listForUser(UUID userId);

    Folder create(UUID userId, String name, UUID parentId, String color, String icon);

    Folder update(UUID userId, UUID folderId, String newName, UUID newParentId, String color, String icon);

    /** Returns true when the folder existed and was deleted. */
    boolean delete(UUID userId, UUID folderId);
}
