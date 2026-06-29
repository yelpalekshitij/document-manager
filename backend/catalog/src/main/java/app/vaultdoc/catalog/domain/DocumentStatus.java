package app.vaultdoc.catalog.domain;

public enum DocumentStatus {
    /** Created by initiate-upload. The blob has not yet been confirmed in object storage. */
    PENDING,
    /** Blob is present and finalize-upload succeeded. The document is visible in the catalog. */
    READY,
    /** Finalize-upload validation failed (size mismatch, virus, etc.). */
    FAILED,
    /** Soft-deleted. Excluded from lists; storage cleanup is async. */
    DELETED
}
