package app.vaultdoc.catalog.service;

/** A tag name, colour, or tag-id list failed the domain rules in {@link TagInput}. */
public class InvalidTagException extends RuntimeException {
    public InvalidTagException(String message) {
        super(message);
    }
}
