package app.vaultdoc.ocr.tesseract;

/**
 * Tesseract could not produce text for an input (non-zero exit, interrupt, I/O).
 *
 * <p>{@link #getMessage()} is for logs and may carry stderr or host paths;
 * {@link #userMessage()} is what may reach {@code ocrFailureReason}.
 */
public class TesseractException extends RuntimeException {

    private final String userMessage;

    public TesseractException(String message) {
        this(message, message, null);
    }

    public TesseractException(String message, Throwable cause) {
        this(message, message, cause);
    }

    public TesseractException(String message, String userMessage, Throwable cause) {
        super(message, cause);
        this.userMessage = userMessage;
    }

    /** Safe to show to the document owner: no stderr, no host paths. */
    public String userMessage() {
        return userMessage;
    }
}
