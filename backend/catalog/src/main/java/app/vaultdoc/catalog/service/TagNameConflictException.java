package app.vaultdoc.catalog.service;

/** Another tag of the same user already has this name (case-insensitively). */
public class TagNameConflictException extends RuntimeException {
    private final String name;

    public TagNameConflictException(String name) {
        super("A tag named \"" + name + "\" already exists");
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
