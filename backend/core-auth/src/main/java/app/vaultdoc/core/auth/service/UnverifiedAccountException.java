package app.vaultdoc.core.auth.service;

public class UnverifiedAccountException extends RuntimeException {

    public UnverifiedAccountException() {
        super("Account email has not been verified");
    }
}
