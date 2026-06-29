package app.vaultdoc.ingest.service;

public class SizeExceededException extends RuntimeException {
    private final long claimedBytes;
    private final long limitBytes;

    public SizeExceededException(long claimedBytes, long limitBytes) {
        super("Upload size " + claimedBytes + " exceeds limit " + limitBytes);
        this.claimedBytes = claimedBytes;
        this.limitBytes = limitBytes;
    }

    public long getClaimedBytes() { return claimedBytes; }
    public long getLimitBytes() { return limitBytes; }
}
