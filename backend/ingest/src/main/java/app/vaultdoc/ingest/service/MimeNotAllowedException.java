package app.vaultdoc.ingest.service;

public class MimeNotAllowedException extends RuntimeException {
    private final String mimeType;

    public MimeNotAllowedException(String mimeType) {
        super("MIME type not allowed: " + mimeType);
        this.mimeType = mimeType;
    }

    public String getMimeType() {
        return mimeType;
    }
}
