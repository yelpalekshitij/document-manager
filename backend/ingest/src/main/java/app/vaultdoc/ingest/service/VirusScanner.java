package app.vaultdoc.ingest.service;

import app.vaultdoc.core.storage.StorageKey;

public interface VirusScanner {

    enum Result { CLEAN, INFECTED, ERROR }

    Result scan(StorageKey key);
}
