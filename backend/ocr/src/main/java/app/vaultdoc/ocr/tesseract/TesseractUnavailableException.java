package app.vaultdoc.ocr.tesseract;

/** The tesseract binary is missing or not executable on this host. */
public class TesseractUnavailableException extends TesseractException {

    public TesseractUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
