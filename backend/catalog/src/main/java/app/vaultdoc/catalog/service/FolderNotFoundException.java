package app.vaultdoc.catalog.service;

import java.util.UUID;

public class FolderNotFoundException extends RuntimeException {
    private final UUID folderId;

    public FolderNotFoundException(UUID folderId) {
        super("Folder " + folderId + " not found");
        this.folderId = folderId;
    }

    public UUID getFolderId() {
        return folderId;
    }
}
