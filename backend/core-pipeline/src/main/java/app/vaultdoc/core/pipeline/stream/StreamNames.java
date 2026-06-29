package app.vaultdoc.core.pipeline.stream;

/**
 * Redis Streams keys used by the document processing pipeline. Every stage's
 * producer and consumer references these constants — no string literals.
 */
public final class StreamNames {

    /** Produced by {@code ingest} after a document is finalized; consumed by {@code ocr}. */
    public static final String UPLOAD_FINALIZED = "vaultdoc:stream:upload-finalized";

    /** Dead-letter for {@link #UPLOAD_FINALIZED} consumers. */
    public static final String UPLOAD_FINALIZED_DLQ = "vaultdoc:stream:upload-finalized:dlq";

    private StreamNames() {}
}
