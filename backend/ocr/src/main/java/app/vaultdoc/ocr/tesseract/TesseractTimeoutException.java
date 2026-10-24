package app.vaultdoc.ocr.tesseract;

/** The tesseract process exceeded its time budget and was killed. */
public class TesseractTimeoutException extends TesseractException {

    public TesseractTimeoutException(String message) {
        super(message);
    }
}
