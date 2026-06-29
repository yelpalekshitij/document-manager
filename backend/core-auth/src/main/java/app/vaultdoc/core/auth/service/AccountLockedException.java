package app.vaultdoc.core.auth.service;

public class AccountLockedException extends RuntimeException {

    public AccountLockedException() {
        super("Account is locked");
    }
}
