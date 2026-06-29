package app.vaultdoc.ingest.service;

import app.vaultdoc.core.storage.StorageKey;

/**
 * Phase 1.2 stub. Returns {@link Result#CLEAN} unconditionally. A real
 * ClamAV-backed implementation will swap in when docker-compose adds the
 * service.
 */
public class NoopVirusScanner implements VirusScanner {

    @Override
    public Result scan(StorageKey key) {
        return Result.CLEAN;
    }
}
