package app.vaultdoc.ocr.tesseract;

/** Tesseract could not produce text for an input (non-zero exit, interrupt, I/O). */
public class TesseractException extends RuntimeException {

    public TesseractException(String message) {
        super(message);
    }

    public TesseractException(String message, Throwable cause) {
        super(message, cause);
    }
}
