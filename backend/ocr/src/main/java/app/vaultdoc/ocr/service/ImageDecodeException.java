package app.vaultdoc.ocr.service;

/** The bytes are not a readable image (garbage, truncated header, unknown format). */
public class ImageDecodeException extends Exception {

    public ImageDecodeException(String message) {
        super(message);
    }

    public ImageDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
