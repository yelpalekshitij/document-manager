package app.vaultdoc.catalog.service;

import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import app.vaultdoc.catalog.domain.Folder;
import app.vaultdoc.catalog.domain.FolderRepository;

/**
 * Seeds the 8 default top-level folders on the first time a user touches the
 * catalog. Idempotent: checks {@code is_system=true} counts before inserting.
 *
 * <p>Lazy on purpose — registration lives in {@code core-auth} and must not
 * import the catalog module.
 */
public class DefaultFolderSeeder {

    private static final List<String> DEFAULTS = List.of(
            "Identity",
            "Financial",
            "Health",
            "Property",
            "Vehicle",
            "Education",
            "Employment",
            "Travel"
    );

    private final FolderRepository folders;

    public DefaultFolderSeeder(FolderRepository folders) {
        this.folders = folders;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureSeeded(UUID userId) {
        if (folders.countSystemForUser(userId) >= DEFAULTS.size()) {
            return;
        }
        for (int i = 0; i < DEFAULTS.size(); i++) {
            var f = new Folder(null, userId, null, DEFAULTS.get(i), null, null, i, true);
            folders.save(f);
        }
    }
}
