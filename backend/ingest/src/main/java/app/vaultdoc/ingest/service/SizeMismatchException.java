package app.vaultdoc.ingest.service;

public class SizeMismatchException extends RuntimeException {
    private final long expectedBytes;
    private final long actualBytes;

    public SizeMismatchException(long expectedBytes, long actualBytes) {
        super("Storage size " + actualBytes + " does not match claimed size " + expectedBytes);
        this.expectedBytes = expectedBytes;
        this.actualBytes = actualBytes;
    }

    public long getExpectedBytes() { return expectedBytes; }
    public long getActualBytes() { return actualBytes; }
}
