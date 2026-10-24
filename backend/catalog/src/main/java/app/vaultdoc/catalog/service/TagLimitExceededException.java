package app.vaultdoc.catalog.service;

public class TagLimitExceededException extends RuntimeException {
    private final int limit;

    public TagLimitExceededException(int limit) {
        super("Tag limit reached: at most " + limit + " tags per user");
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }
}
