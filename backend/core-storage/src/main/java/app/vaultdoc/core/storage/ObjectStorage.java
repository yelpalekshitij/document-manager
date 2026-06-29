package app.vaultdoc.core.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Pluggable object-storage abstraction. The default impl talks to S3 / MinIO,
 * but tests and the future "lite" self-host build can swap in alternatives
 * (filesystem, in-memory) without controllers caring.
 *
 * <p>The two-step upload pattern (presign → client PUT → finalize) is the
 * canonical large-file path across VaultDoc.
 */
public interface ObjectStorage {

    /** Presigned URL the client uploads to directly. The TTL must comfortably cover the slowest expected client. */
    URI presignPut(StorageKey key, String contentType, Duration ttl);

    /** Presigned URL for downloads (or browser viewers). Keep TTL short — a few minutes is plenty. */
    URI presignGet(StorageKey key, Duration ttl);

    /** Returns metadata if the object exists; {@link Optional#empty()} otherwise. */
    Optional<ObjectMetadata> head(StorageKey key);

    /** Idempotent. Deleting a non-existent object is not an error. */
    void delete(StorageKey key);

    /**
     * Pull the object's bytes into memory. For in-process workers (OCR, virus
     * scan, etc.) that need the content directly. Callers must size-check via
     * {@link #head(StorageKey)} before invoking for large objects.
     */
    byte[] download(StorageKey key);

    default boolean exists(StorageKey key) {
        return head(key).isPresent();
    }
}
